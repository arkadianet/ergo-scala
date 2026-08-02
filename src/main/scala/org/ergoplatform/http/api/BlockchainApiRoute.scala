package org.ergoplatform.http.api

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directive, Directive1, Route, ValidationRejection}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.pattern.ask
import io.circe.Json
import io.circe.syntax.EncoderOps
import org.ergoplatform.http.api.SortDirection.{ASC, DESC, Direction, INVALID}
import org.ergoplatform.{ErgoAddress, ErgoAddressEncoder}
import org.ergoplatform.nodeView.ErgoReadersHolder.{GetDataFromHistory, GetReaders, Readers}
import org.ergoplatform.nodeView.history.ErgoHistoryReader
import org.ergoplatform.nodeView.history.extra.ExtraIndexer.ReceivableMessages.GetSegmentThreshold
import org.ergoplatform.nodeView.history.extra.ExtraIndexer.{GlobalBoxIndexKey, GlobalTxIndexKey, IndexedHeightKey, getIndex}
import org.ergoplatform.nodeView.history.extra.IndexedErgoAddressSerializer.hashErgoTree
import org.ergoplatform.nodeView.history.extra.IndexedTokenSerializer.uniqueId
import org.ergoplatform.nodeView.history.extra._
import org.ergoplatform.nodeView.mempool.ErgoMemPoolReader
import org.ergoplatform.nodeView.state.ErgoStateReader
import org.ergoplatform.settings.{ErgoSettings, RESTApiSettings}
import org.ergoplatform.http.api.ApiError.{BadRequest, InternalError}
import org.ergoplatform.modifiers.history.header.Header
import org.ergoplatform.modifiers.history.BlockTransactions
import org.ergoplatform.wallet.interpreter.ErgoInterpreter
import org.ergoplatform.wallet.protocol.Constants
import scorex.core.api.http.ApiResponse
import scorex.db.RangeScanBudgetExceeded
import scorex.util.{ModifierId, bytesToId}
import sigma.ast.ErgoTree
import spire.implicits.cfor

import scala.concurrent.duration.{Duration, SECONDS}
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

case class BlockchainApiRoute(readersHolder: ActorRef, ergoSettings: ErgoSettings, indexerOpt: Option[ActorRef])
                        (implicit val context: ActorRefFactory) extends ErgoBaseApiRoute with ApiCodecs with ApiExtraCodecs {

  val settings: RESTApiSettings = ergoSettings.scorexSettings.restApi

  private lazy val segmentTreshold: Int = indexerOpt.map { indexer =>
    Await.result[Int]((indexer ? GetSegmentThreshold).asInstanceOf[Future[Int]], Duration(3, SECONDS))
  }.getOrElse(0)

  private val paging: Directive[(Int, Int)] = parameters("offset".as[Int] ? 0, "limit".as[Int] ? 5)

  private implicit val sortMarshaller: Unmarshaller[String, Direction] = Unmarshaller.strict[String, Direction] { str =>
    str.toLowerCase match {
      case "asc" => ASC
      case "desc" => DESC
      case _ => INVALID
    }
  }
  private val sortDir: Directive[Tuple1[Direction]] = parameters("sortDirection".as(sortMarshaller) ? DESC)

  /** Rent routes default to ascending order (spec §5 rev. 3), against the shared
    * `sortDir` directive's DESC default above -- do not reuse `sortDir` for these
    * routes, its default is wrong here.
    */
  private val rentSortDir: Directive[Tuple1[Direction]] = parameters("sortDirection".as(sortMarshaller) ? ASC)

  private val unconfirmed: Directive[Tuple1[Boolean]] = parameters("includeUnconfirmed".as[Boolean] ? false)

  /**
    * Total number of boxes/transactions that can be requested at once to avoid too heavy requests ([[BlocksApiRoute.MaxHeaders]])
    */
  private val MaxItems = 16384

  /** Visit budget for descending rent-index scans (spec §5 rev. 3). Forward scans
    * are O(offset+limit) by construction and need no budget; a descending scan
    * over an unauthenticated route must never be allowed to walk the whole
    * key-space, so it gets a finite budget and a 400 instead of a truncated page.
    */
  private val DescendingScanBudget = 1000000L

  override implicit val ergoAddressEncoder: ErgoAddressEncoder = ergoSettings.chainSettings.addressEncoder

  private val ergoAddress: Directive1[ErgoAddress] = entity(as[String]).flatMap(handleErgoAddress)

  private def handleErgoAddress(value: String): Directive1[ErgoAddress] =
    ergoAddressEncoder.fromString(fromJsonOrPlain(value)) match {
      case Success(addr) => provide(addr)
      case _ => reject(ValidationRejection("Wrong address format"))
    }

  override val route: Route =
  if(ergoSettings.nodeSettings.extraIndex)
    pathPrefix("blockchain") {
      getIndexedHeightR ~
      getTxByIdR ~
      getTxByIndexR ~
      getTxsByAddressR ~
      getTxsByAddressGetRoute ~
      getTxRangeR ~
      getBoxByIdR ~
      getBoxByIndexR ~
      getBoxesByTokenIdR ~
      getBoxesByTokenIdUnspentR ~
      getBoxesByAddressR ~
      getBoxesByAddressGetRoute ~
      getBoxesByAddressUnspentR ~
      getBoxesByAddressUnspentGetRoute ~
      getRentEligibleR ~
      getRentMaturingInRangeR ~
      getBoxesByTemplateHashR ~
      getBoxesByTemplateHashUnspentR ~
      getBoxRangeR ~
      getBoxesByErgoTreeR ~
      getBoxesByErgoTreeUnspentR ~
      getTokenInfoByIdR ~
      getTokenInfoByIdsR ~
      getAddressBalanceTotalR ~
      getAddressBalanceTotalGetRoute ~
      getBlockByHeaderIdR ~
      getBlocksByHeaderIdsR
    }
  else
    pathPrefix("blockchain") {
      indexerNotEnabledR
    }

  private def getHistory: Future[ErgoHistoryReader] =
    (readersHolder ? GetDataFromHistory[ErgoHistoryReader](r => r)).mapTo[ErgoHistoryReader]

  private def getHistoryWithMempool: Future[(ErgoHistoryReader,ErgoMemPoolReader)] =
    (readersHolder ? GetReaders).mapTo[Readers].map(r => (r.h, r.m))

  private def getHistoryWithState: Future[(ErgoHistoryReader, ErgoStateReader)] =
    (readersHolder ? GetReaders).mapTo[Readers].map(r => (r.h, r.s))

  private def getAddress(tree: ErgoTree)(history: ErgoHistoryReader): Option[IndexedErgoAddress] =
    history.typedExtraIndexById[IndexedErgoAddress](hashErgoTree(tree))

  private def getAddress(addr: ErgoAddress)(history: ErgoHistoryReader): Option[IndexedErgoAddress] =
    getAddress(addr.script)(history)

  private def getTemplate(hash: ModifierId)(history: ErgoHistoryReader): Option[IndexedContractTemplate] =
    history.typedExtraIndexById[IndexedContractTemplate](hash)

  private def getTxById(id: ModifierId)(history: ErgoHistoryReader): Option[IndexedErgoTransaction] =
    history.typedExtraIndexById[IndexedErgoTransaction](id) match {
      case Some(tx) => Some(tx.retrieveBody(history))
      case None     => None
    }

  private def getTxByIdF(id: ModifierId): Future[Option[IndexedErgoTransaction]] =
    getHistory.map { history =>
      getTxById(id)(history)
    }

  private def getIndexedHeightF: Future[Json] =
    getHistory.map { history =>
      Json.obj(
        "indexedHeight" -> getIndex(ExtraIndexer.IndexedHeightKey, history).getInt.asJson,
        "fullHeight" -> history.fullBlockHeight.asJson
      )
    }

  private def getIndexedHeightR: Route = (pathPrefix("indexedHeight") & get) {
    ApiResponse(getIndexedHeightF)
  }

  private def indexerNotEnabledR: Route = get {
    InternalError("Extra indexing is not enabled")
  }

  private def getTxByIdR: Route = (get & pathPrefix("transaction" / "byId") & modifierId) { id =>
    ApiResponse(getTxByIdF(id))
  }

  private def getTxByIndex(index: Long)(history: ErgoHistoryReader): Option[IndexedErgoTransaction] =
    getTxById(history.typedExtraIndexById[NumericTxIndex](bytesToId(NumericTxIndex.indexToBytes(index))).get.m)(history)

  private def getTxByIndexF(index: Long): Future[Option[IndexedErgoTransaction]] =
    getHistory.map { history =>
      getTxByIndex(index)(history)
    }

  private def getTxByIndexR: Route = (pathPrefix("transaction" / "byIndex" / LongNumber) & get) { index =>
    ApiResponse(getTxByIndexF(index))
  }

  private def getTxsByAddress(addr: ErgoAddress, offset: Int, limit: Int): Future[(Seq[IndexedErgoTransaction],Long)] =
    getHistory.map { history =>
      getAddress(addr)(history) match {
        case Some(addr) => (addr.retrieveTxs(history, offset, limit)(segmentTreshold), addr.txCount(segmentTreshold))
        case None       => (Seq.empty[IndexedErgoTransaction], 0L)
      }
    }

  private def validateAndGetTxsByAddress(address: ErgoAddress,
                                         offset: Int,
                                         limit: Int): Route = {
    if (limit > MaxItems) {
      BadRequest(s"No more than $MaxItems transactions can be requested")
    } else {
      ApiResponse(getTxsByAddress(address, offset, limit))
    }
  }

  private def getTxsByAddressR: Route = (post & pathPrefix("transaction" / "byAddress") & ergoAddress & paging) { (address, offset, limit) =>
    validateAndGetTxsByAddress(address, offset, limit)
  }

  private def getTxsByAddressGetRoute: Route = (pathPrefix("transaction" / "byAddress") & get & addressPass & paging) { (address, offset, limit) =>
    validateAndGetTxsByAddress(address, offset, limit)
  }

  private def getTxRange(offset: Int, limit: Int): Future[Seq[ModifierId]] =
    getHistory.map { history =>
      val base: Long = getIndex(GlobalTxIndexKey, history).getLong - offset
      val txIds: Array[ModifierId] = new Array[ModifierId](limit)
      cfor(0)(_ < limit, _ + 1) { i =>
        txIds(i) = history.typedExtraIndexById[NumericTxIndex](bytesToId(NumericTxIndex.indexToBytes(base - limit + i))).get.m
      }
      txIds.reverse
    }

  private def getTxRangeR: Route = (pathPrefix("transaction" / "range") & paging) { (offset, limit) =>
    if(limit > MaxItems) {
      BadRequest(s"No more than $MaxItems transactions can be requested")
    }else {
      ApiResponse(getTxRange(offset, limit))
    }
  }

  private def getBoxById(id: ModifierId)(history: ErgoHistoryReader): Option[IndexedErgoBox] =
    history.typedExtraIndexById[IndexedErgoBox](id)

  private def getBoxByIdF(id: ModifierId): Future[Option[IndexedErgoBox]] =
    getHistory.map { history =>
      getBoxById(id)(history)
    }

  private def getBoxByIdR: Route = (get & pathPrefix("box" / "byId") & modifierId) { id =>
    ApiResponse(getBoxByIdF(id))
  }

  private def getBoxByIndex(index: Long)(history: ErgoHistoryReader): Option[IndexedErgoBox] =
    getBoxById(history.typedExtraIndexById[NumericBoxIndex](bytesToId(NumericBoxIndex.indexToBytes(index))).get.m)(history)

  private def getBoxByIndexF(index: Long): Future[Option[IndexedErgoBox]] =
    getHistory.map { history =>
      getBoxByIndex(index)(history)
    }

  private def getBoxByIndexR: Route = (pathPrefix("box" / "byIndex" / LongNumber) & get) { index =>
    ApiResponse(getBoxByIndexF(index))
  }

  private def getBoxesByAddress(addr: ErgoAddress, offset: Int, limit: Int): Future[(Seq[IndexedErgoBox],Long)] =
    getHistory.map { history =>
      getAddress(addr)(history) match {
        case Some(addr) => (addr.retrieveBoxes(history, offset, limit)(segmentTreshold).reverse, addr.boxCount(segmentTreshold))
        case None       => (Seq.empty[IndexedErgoBox], 0L)
      }
    }

  private def validateAndGetBoxesByAddress(address: ErgoAddress,
                                           offset: Int,
                                           limit: Int) = {
    if (limit > MaxItems) {
      BadRequest(s"No more than $MaxItems boxes can be requested")
    } else {
      ApiResponse(getBoxesByAddress(address, offset, limit))
    }
  }

  private def getBoxesByAddressR: Route = (post & pathPrefix("box" / "byAddress") & ergoAddress & paging) { (address, offset, limit) =>
    validateAndGetBoxesByAddress(address, offset, limit)
  }

  private def getBoxesByAddressGetRoute: Route = (pathPrefix("box" / "byAddress") & get & addressPass & paging) { (address, offset, limit) =>
    validateAndGetBoxesByAddress(address, offset, limit)
  }

  private def getBoxesByAddressUnspent(
                                        addr: ErgoAddress,
                                        offset: Int,
                                        limit: Int,
                                        sortDir: Direction,
                                        unconfirmed: Boolean,
                                        excludeMempoolSpent: Boolean
                                      ): Future[Seq[IndexedErgoBox]] = {

    getHistoryWithMempool.map { case (history, mempool) =>
      val spentBoxesIdsInMempool = if (excludeMempoolSpent) mempool.spentInputs.map(bytesToId).toSet else Set.empty[ModifierId]

      getAddress(addr)(history)
        .getOrElse(IndexedErgoAddress(hashErgoTree(addr.script)))
        .retrieveUtxos(history, mempool, offset, limit, sortDir, unconfirmed, spentBoxesIdsInMempool)
    }
  }

  private def validateAndGetBoxesByAddressUnspent(address: ErgoAddress,
                                                  offset: Int,
                                                  limit: Int,
                                                  dir: Direction,
                                                  unconfirmed: Boolean, 
                                                  excludeMempoolSpent: Boolean): Route = {
    if (limit > MaxItems) {
      BadRequest(s"No more than $MaxItems boxes can be requested")
    } else if (dir == SortDirection.INVALID) {
      BadRequest("Invalid parameter for sort direction, valid values are \"ASC\" and \"DESC\"")
    } else {
      ApiResponse(getBoxesByAddressUnspent(address, offset, limit, dir, unconfirmed, excludeMempoolSpent))
    }
  }

  private def getBoxesByAddressUnspentR: Route =
    (post & pathPrefix("box" / "unspent" / "byAddress") & ergoAddress & paging & sortDir & unconfirmed & parameter('excludeMempoolSpent.as[Boolean].?)) {
      (address, offset, limit, dir, unconfirmed, excludeMempoolSpentOption) =>
        val excludeMempoolSpent = excludeMempoolSpentOption.getOrElse(false)
        validateAndGetBoxesByAddressUnspent(address, offset, limit, dir, unconfirmed, excludeMempoolSpent)
    }

  private def getBoxesByAddressUnspentGetRoute: Route =
    (pathPrefix("box" / "unspent" / "byAddress") & get & addressPass & paging & sortDir & unconfirmed & parameter('excludeMempoolSpent.as[Boolean].?)) {
      (address, offset, limit, dir, unconfirmed, excludeMempoolSpentOption) =>
        val excludeMempoolSpent = excludeMempoolSpentOption.getOrElse(false)
        validateAndGetBoxesByAddressUnspent(address, offset, limit, dir, unconfirmed, excludeMempoolSpent)
    }

  /** Scan the rent index over [start, end] (both routes construct these from
    * `rentKey`) and collect the first `offset + limit` EMITTED items (i.e.
    * `target`), starting
    * the underlying raw scan at position 0 and re-issuing it with a growing
    * key-counted limit as anomalous rows (missing box / already spent) are
    * skipped along the way. The caller then drops the first `offset` items to
    * get the actual page.
    *
    * This -- rather than passing the route's `offset` straight through as a raw
    * key-offset -- is required for the pagination contract ("page shorter than
    * limit" means "range exhausted", and successive offset-advancing pages tile
    * without gaps or overlaps) to hold in the presence of anomalous rows: since
    * `getRangeWithFilter`'s offset/limit count raw FILTERED KEYS, not emitted
    * items, an anomaly anywhere before the requested window would otherwise
    * shift a raw-offset page by the number of anomalies preceding it, silently
    * breaking continuity between successive pages. Collecting from position 0
    * up to `target` items and slicing in item-space sidesteps that -- both
    * `offset` and `limit` end up counting the same thing (emitted items).
    *
    * Isolated anomalies are logged and skipped, never fatal; only crossing the
    * anomaly budget (more than MaxItems skipped rows total) aborts the request,
    * since that signals a massively desynced index rather than a stray orphan
    * row.
    */
  private def scanRentRange(history: ErgoHistoryReader,
                            start: Array[Byte],
                            end: Array[Byte],
                            offset: Int,
                            limit: Int,
                            reverse: Boolean,
                            factor: Int): Either[Route, Vector[(IndexedErgoBox, Int, Int)]] = {
    val visitBudget = if (reverse) DescendingScanBudget else Long.MaxValue
    val target = offset + limit

    val acc = Vector.newBuilder[(IndexedErgoBox, Int, Int)]
    var off = 0
    var lim = target
    var totalEmitted = 0
    var totalSkipped = 0
    var exhausted = false
    var budgetExceeded = false

    while (!exhausted && !budgetExceeded && totalEmitted < target && lim > 0) {
      val rows = ExtraIndexer.rentRange(history, start, end, off, lim, reverse, visitBudget)
      val k = rows.length
      var emittedThisRound = 0
      rows.foreach { case (_, valueBytes) =>
        val boxId = bytesToId(valueBytes)
        history.typedExtraIndexById[IndexedErgoBox](boxId) match {
          case Some(iEb) if !iEb.isSpent =>
            val size = iEb.box.bytes.length
            val fee = ErgoInterpreter.storageFee(factor, size)
            acc += ((iEb, size, fee))
            emittedThisRound += 1
          case Some(_) =>
            log.debug(s"rent range scan: skipping already-spent box $boxId in rent index")
            totalSkipped += 1
          case None =>
            log.warn(s"rent range scan: missing box $boxId referenced by rent index")
            totalSkipped += 1
        }
      }
      totalEmitted += emittedThisRound
      if (totalSkipped > MaxItems) {
        budgetExceeded = true
      } else if (k < lim) {
        exhausted = true
      } else {
        off += k
        lim = target - totalEmitted
      }
    }

    if (budgetExceeded) {
      Left(InternalError(s"rent index anomaly budget exceeded ($totalSkipped skipped rows); index may be desynced"))
    } else {
      Right(acc.result().drop(offset))
    }
  }

  private def rentEligibleResponse(atHeightOpt: Option[Int],
                                   offset: Int,
                                   limit: Int,
                                   dir: Direction,
                                   history: ErgoHistoryReader,
                                   state: ErgoStateReader): Either[Route, Json] = {
    val indexedHeight = getIndex(IndexedHeightKey, history).getInt
    val atHeight = atHeightOpt.getOrElse(indexedHeight + 1)
    val cutoff = atHeight - Constants.StoragePeriod

    val factorAtHeight = StorageFeeFactorResolver.factorAt(atHeight, history, state.parameters, ergoSettings)

    def baseFields(items: Json): Json = Json.obj(
      "items" -> items,
      "atHeight" -> atHeight.asJson,
      "storageFeeFactor" -> factorAtHeight.factor.asJson,
      "storageFeeFactorSource" -> factorAtHeight.source.asJson,
      "indexedHeight" -> indexedHeight.asJson,
      "fullHeight" -> history.fullBlockHeight.asJson
    )

    // rentKey requires non-negative components; a cutoff below zero (every chain
    // below StoragePeriod, i.e. every test chain and every real node until height
    // ~1.05M) must short-circuit BEFORE any key is constructed.
    if (cutoff < 0) {
      Right(baseFields(Json.arr()))
    } else {
      val reverse = dir == DESC
      val start = ExtraIndexer.rentKey(0, 0L)
      val end = ExtraIndexer.rentKey(cutoff, Long.MaxValue)
      scanRentRange(history, start, end, offset, limit, reverse, factorAtHeight.factor).map { items =>
        baseFields(items.asJson)
      }
    }
  }

  /** `rentMaturingInRange`'s counterpart of `rentEligibleResponse`: boxes whose
    * creation height + StoragePeriod falls within [fromHeight, toHeight], i.e.
    * `creationHeight` in [loCutoff, hiCutoff]. `fromHeight == toHeight` is a
    * valid single-height slice (subsumes a separate "matures at exactly H"
    * route); range validation must use `>=`, not `>`, to accept it.
    */
  private def rentMaturingInRangeResponse(fromHeight: Int,
                                          toHeight: Int,
                                          offset: Int,
                                          limit: Int,
                                          dir: Direction,
                                          history: ErgoHistoryReader,
                                          state: ErgoStateReader): Either[Route, Json] = {
    val indexedHeight = getIndex(IndexedHeightKey, history).getInt
    val loCutoff = math.max(0, fromHeight - Constants.StoragePeriod)
    val hiCutoff = toHeight - Constants.StoragePeriod

    val factorAtHeight = StorageFeeFactorResolver.factorAt(toHeight, history, state.parameters, ergoSettings)

    def baseFields(items: Json): Json = Json.obj(
      "items" -> items,
      "fromHeight" -> fromHeight.asJson,
      "toHeight" -> toHeight.asJson,
      "storageFeeFactor" -> factorAtHeight.factor.asJson,
      "storageFeeFactorSource" -> factorAtHeight.source.asJson,
      "indexedHeight" -> indexedHeight.asJson,
      "fullHeight" -> history.fullBlockHeight.asJson
    )

    // Same rentKey non-negativity trap as rentEligible: hiCutoff < 0 must
    // short-circuit BEFORE any key is constructed.
    if (hiCutoff < 0) {
      Right(baseFields(Json.arr()))
    } else {
      val reverse = dir == DESC
      val start = ExtraIndexer.rentKey(loCutoff, 0L)
      val end = ExtraIndexer.rentKey(hiCutoff, Long.MaxValue)
      scanRentRange(history, start, end, offset, limit, reverse, factorAtHeight.factor).map { items =>
        baseFields(items.asJson)
      }
    }
  }

  /** Shared by both rent routes: 503-gate on an in-progress backfill, run `f`,
    * translate a descending-scan budget overrun into 400, and complete with the
    * resulting JSON. A gate wired into one handler and forgotten in the other is
    * exactly the kind of regression this sharing is meant to prevent.
    */
  private def withRentBackfillGate(f: (ErgoHistoryReader, ErgoStateReader) => Either[Route, Json]): Route = {
    onSuccess(getHistoryWithState) { case (history, state) =>
      ExtraIndexer.rentBackfillCursor(history) match {
        case Some(c) =>
          complete(StatusCodes.ServiceUnavailable -> s"rent index backfill in progress: $c")
        case None =>
          Try(f(history, state)) match {
            case Success(Right(json)) => ApiResponse(json)
            case Success(Left(errorRoute)) => errorRoute
            case Failure(_: RangeScanBudgetExceeded) =>
              BadRequest("descending scan budget exceeded; use sortDirection=asc or narrow the range")
            case Failure(e) => throw e
          }
      }
    }
  }

  private def getRentEligible(atHeightOpt: Option[Int],
                              offset: Int,
                              limit: Int,
                              dir: Direction): Route =
    withRentBackfillGate((history, state) => rentEligibleResponse(atHeightOpt, offset, limit, dir, history, state))

  private def validateAndGetRentEligible(atHeightOpt: Option[Int],
                                         offset: Int,
                                         limit: Int,
                                         dir: Direction): Route = {
    if (limit > MaxItems) {
      BadRequest(s"No more than $MaxItems boxes can be requested")
    } else if (offset < 0) {
      BadRequest("offset must not be negative")
    } else if (atHeightOpt.exists(_ <= 0)) {
      BadRequest("atHeight must be positive")
    } else if (dir == SortDirection.INVALID) {
      BadRequest("Invalid parameter for sort direction, valid values are \"ASC\" and \"DESC\"")
    } else {
      getRentEligible(atHeightOpt, offset, limit, dir)
    }
  }

  private def getRentEligibleR: Route =
    (pathPrefix("box" / "unspent" / "rentEligible") & get &
      parameters("atHeight".as[Int].?) & paging & rentSortDir) { (atHeightOpt, offset, limit, dir) =>
      validateAndGetRentEligible(atHeightOpt, offset, limit, dir)
    }

  private def getRentMaturingInRange(fromHeight: Int,
                                     toHeight: Int,
                                     offset: Int,
                                     limit: Int,
                                     dir: Direction): Route =
    withRentBackfillGate((history, state) =>
      rentMaturingInRangeResponse(fromHeight, toHeight, offset, limit, dir, history, state))

  private def validateAndGetRentMaturingInRange(fromHeight: Int,
                                                toHeight: Int,
                                                offset: Int,
                                                limit: Int,
                                                dir: Direction): Route = {
    if (limit > MaxItems) {
      BadRequest(s"No more than $MaxItems boxes can be requested")
    } else if (offset < 0) {
      BadRequest("offset must not be negative")
    } else if (fromHeight < 0) {
      BadRequest("fromHeight must not be negative")
    } else if (fromHeight > toHeight) {
      BadRequest("fromHeight must not be greater than toHeight")
    } else if (dir == SortDirection.INVALID) {
      BadRequest("Invalid parameter for sort direction, valid values are \"ASC\" and \"DESC\"")
    } else {
      getRentMaturingInRange(fromHeight, toHeight, offset, limit, dir)
    }
  }

  private def getRentMaturingInRangeR: Route =
    (pathPrefix("box" / "unspent" / "rentMaturingInRange") & get &
      parameters("fromHeight".as[Int], "toHeight".as[Int]) & paging & rentSortDir) {
      (fromHeight, toHeight, offset, limit, dir) =>
        validateAndGetRentMaturingInRange(fromHeight, toHeight, offset, limit, dir)
    }

  private def getBoxesByTemplateHash(templateHash: ModifierId, offset: Int, limit: Int): Future[(Seq[IndexedErgoBox],Long)] =
    getHistory.map { history =>
      getTemplate(templateHash)(history) match {
        case Some(iCt) => (iCt.retrieveBoxes(history, offset, limit)(segmentTreshold), iCt.boxCount(segmentTreshold))
        case None      => (Seq.empty[IndexedErgoBox], 0L)
      }
    }

  private def getBoxesByTemplateHashR: Route =
    (get & pathPrefix("box" / "byTemplateHash") & modifierId & paging) {
      (template, offset, limit) =>
        if(limit > MaxItems) {
          BadRequest(s"No more than $MaxItems boxes can be requested")
        } else {
          ApiResponse(getBoxesByTemplateHash(template, offset, limit))
        }
    }

  private def getBoxesByTemplateHashUnspent(templateHash: ModifierId, offset: Int, limit: Int, sortDir: Direction, unconfirmed: Boolean, excludeMempoolSpent: Boolean): Future[Seq[IndexedErgoBox]] =
    getHistoryWithMempool.map { case (history, mempool) =>
      val spentBoxesIdsInMempool = if (excludeMempoolSpent) mempool.spentInputs.map(bytesToId).toSet else Set.empty[ModifierId]
      getTemplate(templateHash)(history)
        .getOrElse(IndexedContractTemplate(templateHash))
        .retrieveUtxos(history, mempool, offset, limit, sortDir, unconfirmed, spentBoxesIdsInMempool)
    }

  private def getBoxesByTemplateHashUnspentR: Route =
    (get & pathPrefix("box" / "unspent" / "byTemplateHash") & modifierId & paging & sortDir & unconfirmed & parameter('excludeMempoolSpent.as[Boolean].?)) {
      (template, offset, limit, dir, unconfirmed, excludeMempoolSpentOption) =>
        if(limit > MaxItems) {
          BadRequest(s"No more than $MaxItems boxes can be requested")
        } else {
          val excludeMempoolSpent = excludeMempoolSpentOption.getOrElse(false)
          ApiResponse(getBoxesByTemplateHashUnspent(template, offset, limit, dir, unconfirmed, excludeMempoolSpent))
        }
    }


  private def getBoxRange(offset: Int, limit: Int): Future[Seq[ModifierId]] =
    getHistory.map { history =>
      val base: Long = getIndex(GlobalBoxIndexKey, history).getLong - offset
      val boxIds: Array[ModifierId] = new Array[ModifierId](limit)
      cfor(0)(_ < limit, _ + 1) { i =>
        boxIds(i) = history.typedExtraIndexById[NumericBoxIndex](bytesToId(NumericBoxIndex.indexToBytes(base - limit + i))).get.m
      }
      boxIds.reverse
    }

  private def getBoxRangeR: Route = (pathPrefix("box" / "range") & paging) { (offset, limit) =>
    if(limit > MaxItems) {
      BadRequest(s"No more than $MaxItems boxes can be requested")
    }else {
      ApiResponse(getBoxRange(offset, limit))
    }
  }

  private def getBoxesByErgoTree(tree: ErgoTree, offset: Int, limit: Int): Future[(Seq[IndexedErgoBox],Long)] =
    getHistory.map { history =>
      getAddress(tree)(history) match {
        case Some(iEa) => (iEa.retrieveBoxes(history, offset, limit)(segmentTreshold).reverse, iEa.boxCount(segmentTreshold))
        case None      => (Seq.empty[IndexedErgoBox], 0L)
      }
    }

  private def getBoxesByErgoTreeR: Route = (post & pathPrefix("box" / "byErgoTree") & ergoTree & paging) { (tree, offset, limit) =>
    if(limit > MaxItems) {
      BadRequest(s"No more than $MaxItems boxes can be requested")
    }else {
      ApiResponse(getBoxesByErgoTree(tree, offset, limit))
    }
  }

  private def getBoxesByErgoTreeUnspent(tree: ErgoTree, offset: Int, limit: Int, sortDir: Direction, unconfirmed: Boolean, excludeMempoolSpent: Boolean): Future[Seq[IndexedErgoBox]] =
    getHistoryWithMempool.map { case (history, mempool) =>
      val spentBoxesIdsInMempool = if (excludeMempoolSpent) mempool.spentInputs.map(bytesToId).toSet else Set.empty[ModifierId]
      getAddress(tree)(history)
        .getOrElse(IndexedErgoAddress(hashErgoTree(tree)))
        .retrieveUtxos(history, mempool, offset, limit, sortDir, unconfirmed, spentBoxesIdsInMempool)
    }

  private def getBoxesByErgoTreeUnspentR: Route = (post & pathPrefix("box" / "unspent" / "byErgoTree") & ergoTree & paging & sortDir & unconfirmed & parameter('excludeMempoolSpent.as[Boolean].?)) { (tree, offset, limit, dir, unconfirmed, excludeMempoolSpentOption) =>
    if(limit > MaxItems) {
      BadRequest(s"No more than $MaxItems boxes can be requested")
    }else if (dir == SortDirection.INVALID) {
      BadRequest("Invalid parameter for sort direction, valid values are 'ASC' and 'DESC'")
    }else {
      val excludeMempoolSpent = excludeMempoolSpentOption.getOrElse(false)
      ApiResponse(getBoxesByErgoTreeUnspent(tree, offset, limit, dir, unconfirmed, excludeMempoolSpent))
    }
  }

  private def getTokenInfoByIds(ids: Seq[ModifierId]): Future[Seq[IndexedToken]] = {
    getHistory.map { history =>
      ids.flatMap(id => history.typedExtraIndexById[IndexedToken](uniqueId(id)))
    }
  }

  private def getTokenInfoById(id: ModifierId): Future[Option[IndexedToken]] = {
    getHistory.map { history =>
      history.typedExtraIndexById[IndexedToken](uniqueId(id))
    }
  }

  private def getTokenInfoByIdR: Route = (get & pathPrefix("token" / "byId") & modifierId) { id =>
    ApiResponse(getTokenInfoById(id))
  }

  private def getTokenInfoByIdsR: Route = (post & pathPrefix("tokens") & entity(as[Seq[ModifierId]])) { ids =>
    ApiResponse(getTokenInfoByIds(ids))
  }

  private def getBoxesByTokenId(id: ModifierId, offset: Int, limit: Int): Future[(Seq[IndexedErgoBox],Long)] =
    getHistory.map { history =>
      history.typedExtraIndexById[IndexedToken](uniqueId(id)) match {
        case Some(token) => (token.retrieveBoxes(history, offset, limit)(segmentTreshold), token.boxCount(segmentTreshold))
        case None        => (Seq.empty[IndexedErgoBox], 0L)
      }
    }

  private def getBoxesByTokenIdR: Route = (get & pathPrefix("box" / "byTokenId") & modifierId & paging) { (id, offset, limit) =>
    ApiResponse(getBoxesByTokenId(id, offset, limit))
  }

  private def getBoxesByTokenIdUnspent(id: ModifierId, offset: Int, limit: Int, sortDir: Direction, unconfirmed: Boolean, excludeMempoolSpent: Boolean): Future[Seq[IndexedErgoBox]] =
    getHistoryWithMempool.map { case (history, mempool) =>
      val spentBoxesIdsInMempool = if (excludeMempoolSpent) mempool.spentInputs.map(bytesToId).toSet else Set.empty[ModifierId]
      history.typedExtraIndexById[IndexedToken](uniqueId(id))
        .getOrElse(IndexedToken(id))
        .retrieveUtxos(history, mempool, offset, limit, sortDir, unconfirmed, spentBoxesIdsInMempool)
    }

  private def getBoxesByTokenIdUnspentR: Route = (get & pathPrefix("box" / "unspent" / "byTokenId") & modifierId & paging & sortDir & unconfirmed & parameter('excludeMempoolSpent.as[Boolean].?)) { (id, offset, limit, dir, unconfirmed, excludeMempoolSpentOption) =>
    val excludeMempoolSpent = excludeMempoolSpentOption.getOrElse(false)
    if (limit > MaxItems) {
      BadRequest(s"No more than $MaxItems boxes can be requested")
    } else if (dir == SortDirection.INVALID) {
      BadRequest("Invalid parameter for sort direction, valid values are 'ASC' and 'DESC'")
    } else {
      ApiResponse(getBoxesByTokenIdUnspent(id, offset, limit, dir, unconfirmed, excludeMempoolSpent))
    }
  }

  private def getUnconfirmedForAddress(address: ErgoAddress)(mempool: ErgoMemPoolReader): BalanceInfo = {
    val bal: BalanceInfo = BalanceInfo()
    mempool.getAll.map(_.transaction).foreach(tx => {
      tx.outputs.foreach(box => {
        if(address.equals(ExtraIndexer.getAddress(box.ergoTree))) bal.add(box)
      })
    })
    bal
  }

  private def getAddressBalanceTotal(address: ErgoAddress): Future[(BalanceInfo,BalanceInfo)] = {
    getHistoryWithMempool.map { case (history, mempool) =>
      getAddress(address)(history) match {
        case Some(addr) =>
          (addr.balanceInfo.get.retrieveAdditionalTokenInfo(history), getUnconfirmedForAddress(address)(mempool).retrieveAdditionalTokenInfo(history))
        case None =>
          (BalanceInfo(), getUnconfirmedForAddress(address)(mempool).retrieveAdditionalTokenInfo(history))
      }
    }
  }

  private def getAddressBalanceTotalR: Route = (post & pathPrefix("balance") & ergoAddress) { address =>
    ApiResponse(getAddressBalanceTotal(address))
  }

  /** Parses address in the url (i.e. `balanceForAddress/{address}` into [[ErgoAddress]] using [[ErgoAddressEncoder]]. */
  private val addressPass: Directive1[ErgoAddress] = pathPrefix(Segment).flatMap(handleErgoAddress)

  private def getAddressBalanceTotalGetRoute: Route =
    (pathPrefix("balanceForAddress") & get & addressPass) { address =>
      ApiResponse(getAddressBalanceTotal(address))
    }

  // common helper code used in both getIndexedBlockByHeaderId / getIndexedBlocksByHeaderId
  private def getIndexedBlockByHeader(history: ErgoHistoryReader, headerId: ModifierId) = {
    history.typedModifierById[Header](headerId).flatMap { header =>

      val blockTransactionsOpt = history.typedModifierById[BlockTransactions](header.transactionsId)

      blockTransactionsOpt.flatMap { blockTransactions =>
        val resolvedTransactions = blockTransactions.txs.flatMap { tx =>
          history
            .typedExtraIndexById[IndexedErgoTransaction](tx.id)
            .map(_.retrieveBody(history))
        }

        if(resolvedTransactions.length == blockTransactions.txs.length) {
          Some(IndexedBlock(
            header,
            resolvedTransactions,
            header.height,
            blockTransactions.size
          ))
        } else {
          None
        }
      }
    }
  }

  private def getIndexedBlockByHeaderId(headerId: ModifierId): Future[Option[IndexedBlock]] = {
    getHistory.map { history =>
      getIndexedBlockByHeader(history, headerId)
    }
  }

  private def getIndexedBlocksByHeaderIds(headerIds: Seq[ModifierId]): Future[Seq[IndexedBlock]] =
    getHistory.map { history =>
      headerIds.flatMap { headerId =>
        getIndexedBlockByHeader(history, headerId)
      }
    }

  private def getBlockByHeaderIdR: Route =
    (get & pathPrefix("block" / "byHeaderId") & modifierId) { id =>
      ApiResponse(getIndexedBlockByHeaderId(id))
    }

  private def getBlocksByHeaderIdsR: Route =
    (post & path("block" / "byHeaderIds") & entity(as[Seq[ModifierId]])) { headerIds =>
      if (headerIds.length > MaxItems) {
        BadRequest(s"No more than $MaxItems blocks can be requested")
      } else {
        ApiResponse(getIndexedBlocksByHeaderIds(headerIds))
      }
    }

}
