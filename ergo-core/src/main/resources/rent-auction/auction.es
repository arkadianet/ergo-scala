{
  val seed = SELF.R8[Long].get
  val increment = __INCREMENT__L
  val minimumBid = __MIN_BID__L
  val allowance = __CLOSE_ALLOWANCE__L
  val collector = SELF.R9[Coll[Byte]].get
  val depositHash = fromBase64("__DEPOSIT_HASH__")
  val lot = SELF.R4[Coll[Byte]].get
  val limits = SELF.R5[Coll[Int]].get
  val bid = SELF.R6[Long].get
  val recipient = SELF.R7[Coll[Byte]].get
  val oneLot = INPUTS(0).id == SELF.id &&
    INPUTS.slice(1, INPUTS.size).forall { (b: Box) => b.tokens.size == 0 }
  val wellFormed = seed > 0L && SELF.value >= allowance && seed <= SELF.value - allowance &&
    collector.size > 0 && collector.size <= 256 && lot.size == 32 &&
    SELF.tokens.size > 0 && SELF.tokens.size <= __TOKENS_PER_LOT__ &&
    bid >= 0L && SELF.value - allowance - seed == bid && limits.size == 2 && limits(0) <= limits(1) &&
    limits(0) >= SELF.creationInfo._1 &&
    limits(1).toLong - SELF.creationInfo._1.toLong <= __MAXIMUM_WINDOW__L &&
    ((bid == 0L && recipient.size == 0) ||
      (bid >= minimumBid && recipient.size > 0 && recipient.size <= 256))
  val valid = if (HEIGHT < limits(0)) {
    val next = OUTPUTS(0)
    val nextBid = next.R6[Long].get
    val nextRecipient = next.R7[Coll[Byte]].get
    val nextEnd = if (limits(1) - HEIGHT <= __EXTENSION__) limits(1)
      else max(limits(0), HEIGHT + __EXTENSION__)
    val refund = if (bid == 0L) true else {
      val out = OUTPUTS(1)
      out.value == bid && out.tokens.size == 0 && out.creationInfo._1 == HEIGHT &&
        out.propositionBytes == recipient && out.R4[Coll[Byte]].get == SELF.id
    }
    oneLot && next.propositionBytes == SELF.propositionBytes && next.tokens == SELF.tokens &&
      next.creationInfo._1 == HEIGHT && next.R4[Coll[Byte]].get == lot &&
      next.R5[Coll[Int]].get == Coll(nextEnd, limits(1)) &&
      next.R8[Long].get == seed && next.R9[Coll[Byte]].get == collector &&
      nextBid >= minimumBid && nextBid > bid && nextBid - bid >= increment &&
      next.value >= allowance && seed <= next.value - allowance &&
      next.value - allowance - seed == nextBid && nextRecipient.size > 0 &&
      nextRecipient.size <= 256 && refund
  } else {
    val k = getVar[Int](0).get
    val allocatedFee = getVar[Long](1).get
    val bytePrice = getVar[Int](2).get
    if (k < 0 || k >= OUTPUTS.size) false else {
      val returned = OUTPUTS(k)
      val share = bid / __COLLECTOR_SHARE_DENOMINATOR__L
      val returnOk = allocatedFee >= 0L && allocatedFee <= allowance &&
        bytePrice >= 0 && bytePrice <= __MAX_BYTE_PRICE__ &&
        returned.propositionBytes == collector && returned.tokens.size == 0 &&
        returned.creationInfo._1 == HEIGHT &&
        returned.R4[Coll[Byte]].get == SELF.id &&
        returned.value == seed + allowance - allocatedFee + share
      val saleOk = if (bid == 0L) true else if (k > OUTPUTS.size - 3) false else {
        val winner = OUTPUTS(k + 1)
        val proceeds = OUTPUTS(k + 2)
        val carrierBytes = __PAYOUT_OVERHEAD_BYTES__L + recipient.size.toLong +
          __TOKEN_ENTRY_BYTES__L * SELF.tokens.size.toLong
        val carrier = max(1L, bytePrice.toLong * carrierBytes)
        winner.propositionBytes == recipient && winner.tokens == SELF.tokens &&
          winner.value == carrier && winner.creationInfo._1 == HEIGHT &&
          winner.R4[Coll[Byte]].get == SELF.id &&
          blake2b256(proceeds.propositionBytes) == depositHash &&
          proceeds.value == bid - share - carrier &&
          proceeds.value >= __MIN_DEPOSIT_VALUE__L &&
          proceeds.tokens.size == 0 && proceeds.creationInfo._1 == HEIGHT &&
          proceeds.R4[Coll[Byte]].get == SELF.id
      }
      returnOk && saleOk
    }
  }
  sigmaProp(wellFormed && valid)
}
