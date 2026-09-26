{
  val nft = fromBase64("__NFT__")
  val reserveTree = fromBase64("__RESERVE__")
  val feeTree = fromBase64("__FEE__")
  val reserve = INPUTS(0)
  val rest = INPUTS.slice(1, INPUTS.size)
  val deposits = rest.filter { (b: Box) => b.propositionBytes == SELF.propositionBytes }
  val sponsors = rest.filter { (b: Box) => b.propositionBytes != SELF.propositionBytes }
  val budget = __MERGE_BUDGET__L
  val principal = deposits.fold(0L, { (sum: Long, b: Box) =>
    sum + (b.value - budget)
  })
  val feeTopUp = sponsors.fold(0L, { (sum: Long, b: Box) => sum + b.value })
  val next = OUTPUTS(0)
  val fee = OUTPUTS(1)
  sigmaProp(
    INPUTS.size >= 2 && INPUTS.size <= 12 && OUTPUTS.size == 2 &&
    deposits.size >= 1 && deposits.size <= 10 && sponsors.size <= 1 &&
    sponsors.forall { (b: Box) => b.tokens.size == 0 } &&
    SELF.id != reserve.id &&
    deposits.forall { (b: Box) =>
      b.propositionBytes == SELF.propositionBytes && b.tokens.size == 0 &&
        b.R4[Coll[Byte]].get.size == 32 && b.value >= __MIN_BID__L + budget
    } &&
    reserve.propositionBytes == reserveTree && reserve.tokens.size >= 1 &&
    reserve.tokens(0) == (nft, 1L) &&
    next.propositionBytes == reserveTree && next.tokens == Coll((nft, 1L)) &&
    next.creationInfo._1 == HEIGHT && next.value > reserve.value &&
    next.value - reserve.value == principal &&
    fee.propositionBytes == feeTree && fee.tokens.size == 0 &&
    fee.value == budget * deposits.size.toLong + feeTopUp && fee.value <= 10000000L
  )
}
