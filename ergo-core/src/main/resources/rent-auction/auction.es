{
  val seed = SELF.R8[Long].get
  val increment = __INCREMENT__L
  val minimumBid = __MIN_BID__L
  val carrier = __CARRIER__L
  val depositTree = fromBase64("__DEPOSIT__")
  val lot = SELF.R4[Coll[Byte]].get
  val limits = SELF.R5[Coll[Int]].get
  val bid = SELF.R6[Long].get
  val recipient = SELF.R7[Coll[Byte]].get
  val oneLot = INPUTS(0).id == SELF.id &&
    INPUTS.slice(1, INPUTS.size).forall { (b: Box) => b.tokens.size == 0 }
  val wellFormed = seed >= __SEED__L && lot.size == 32 &&
    SELF.tokens.size > 0 && SELF.tokens.size <= __TOKENS_PER_LOT__ &&
    bid >= 0L && SELF.value - seed == bid && limits.size == 2 && limits(0) <= limits(1) &&
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
      out.value == bid && out.tokens.size == 0 &&
        out.propositionBytes == recipient && out.R4[Coll[Byte]].get == SELF.id
    }
    next.propositionBytes == SELF.propositionBytes && next.tokens == SELF.tokens &&
      next.creationInfo._1 == HEIGHT && next.R4[Coll[Byte]].get == lot &&
      next.R5[Coll[Int]].get == Coll(nextEnd, limits(1)) &&
      next.R8[Long].get == seed &&
      nextBid >= minimumBid && nextBid > bid && nextBid - bid >= increment &&
      next.value - seed == nextBid && nextRecipient.size > 0 &&
      nextRecipient.size <= 256 && refund
  } else if (bid == 0L) {
    OUTPUTS.forall { (b: Box) =>
      b.tokens.size == 0 && b.propositionBytes != SELF.propositionBytes
    }
  } else {
    val winner = OUTPUTS(0)
    val proceeds = OUTPUTS(1)
    winner.propositionBytes == recipient && winner.tokens == SELF.tokens &&
      winner.value >= carrier && winner.R4[Coll[Byte]].get == SELF.id &&
      proceeds.propositionBytes == depositTree &&
      proceeds.value - __MERGE_BUDGET__L >= bid &&
      proceeds.tokens.size == 0 && proceeds.creationInfo._1 == HEIGHT &&
      proceeds.R4[Coll[Byte]].get == SELF.id
  }
  sigmaProp(oneLot && wellFormed && valid)
}
