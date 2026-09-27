# Collection economics

This document separates the earlier draft's incentive problem from the implemented
schema-2 design. Current amounts come from `RentAuctionContracts.scala` and
`RentAuctionRules.scala`. Current lifecycle results and serialized sizes come from
`RentAuctionEconomicsSpec`, measured by the reviewer with native transaction
validation and activated `ErgoState.execTransactions`.

## Why the earlier design changed

The earlier draft required a fixed 0.005 ERG seed for each source box's lot. It paid
that seed to whoever closed, funded sale costs from it, and gave the collector no
bid share. A producer collecting a typical 0.001 ERG box lost 0.004 ERG when another
party closed first. That comparison treats its own-block collection fee as an
internal payment. A sale consumed more of the seed instead of improving the
collector's recovery. Collection remained optional, so unattractive boxes could
stay in the UTXO set.

The economic review used a mainnet census at height 1,881,892:

| Population | Fully consumed token boxes | Below the old 0.005 ERG break-even |
| --- | ---: | ---: |
| Historical | 130,803 | 93.4% |
| Maturing in the next 12 months | 109,682 | 94.0% |

Also, 85.6% of historical fully consumed token boxes held one token entry. These
figures describe box ERG and token counts, not token market values.

Census source: **kadia explorer index and rent-claim classifier, heights 1,051,232
to 1,881,874, measured 2026-09-27**. The checked economic review of the earlier draft
supplies this comparison; its old constants are not the implemented rules.

## Implemented changes

* Return seed principal to the script recorded in R9. Closing cannot divert it to
  a rival. A separate 0.002 ERG allowance pays the close fee and returns any remainder.
* Size principal from the lot's serializer envelope at the active byte price. Keep
  a floor covering the collector return at byte price 10,000. Recalculate that
  active-price requirement only at opening.
* Aggregate auctionable tokens across fully consumed inputs. A lot may contain up
  to 32 distinct token entries; many sources can share it. An explicit partition
  can separate valuable assets. Quantities must balance exactly across all lots.
* Pay the winner's ERG carrier and collector share from the bid. The proceeds
  deposit holds the balance, including its 0.001 ERG native merge-fee budget.
* Close up to 32 lots together with one bounded native fee, allocated deterministically
  from the allowances. There is no private closer or merger bounty.

Ordinary rent still goes to the producer's committed beneficiary. A collector that
is not that beneficiary does not receive that income. The table below assumes
both roles belong to the same operator. It counts fees as costs, even when that
operator might mine one of the transactions itself. The submitter chooses a valid
close fee within the cap; the table fixes fees rather than guaranteeing an unused
allowance amount.

## Measured results

Each source holds 0.001 ERG and one token entry. Each row uses one collection and
one close per lot, a P2PK collector, and a 256-byte winning-recipient script for
sales. A minimum sale has a gross bid of 0.05 ERG and collector share of 0.005 ERG.
The bidder pays its own bid fee; it is not charged to this collector result.

| Byte price | Fee per transaction, ERG | Sources/lot | No bid, ERG/source | Minimum sale, ERG/source |
| ---: | ---: | ---: | ---: | ---: |
| 360 | 0.001 | 1 | -0.001 | +0.004 |
| 360 | 0.001 | 20 | +0.0009 | +0.00115 |
| 360 | 0.001 | 32 | +0.0009375 | +0.00109375 |
| 10,000 | 0.002 | 1 | -0.003 | +0.002 |
| 10,000 | 0.002 | 20 | +0.0008 | +0.00105 |
| 10,000 | 0.002 | 32 | +0.000875 | +0.00103125 |

For `n` sources, collection fee `Fc`, allocated closing fee `Fx` and bid `B`, the
collector's total result in nanoERG is:

```
n * 1,000,000 - Fc - Fx + floor(B / 10)
```

For no bid, `B = 0`. Divide by `n` and by 1,000,000,000 for ERG per source. Seed and
unused allowance cancel once the close returns them. For example, the 20-source
no-bid result at price 360 is `(20 * 0.001 - 0.001 - 0.001) / 20 = 0.0009 ERG`.
A minimum sale adds `0.005 / 20`, giving `0.00115 ERG`. These are the assertions in
`RentAuctionEconomicsSpec`, not estimates of token prices or bid demand.

## Byte price, fees and bid probability

For the measured P2PK collector, opening principal is 1,640,000 nanoERG at byte
price 360. At 10,000 it ranges from 9,880,000 for one token to 21,350,000 for 32.
The separate 2,000,000 allowance is added to those amounts. The higher principal
is locked capital, not a permanent expense if the lot closes.

A native fee box measures 147 bytes. At price 10,000 its dust is 0.00147 ERG, so
0.001 ERG fees are invalid. The high-price measurements use 0.002 ERG fees. Thus
+0.0009 ERG per source at 20 sources per lot is not invariant under byte-price votes:
it falls to +0.0008 with these spendable fees. Batch closing can spread its one fee
across more lots, subject to size, cost and the 32-lot limit.

Let `p` be the probability of a minimum sale. Under these fixed-fee assumptions,
expected result per source is the no-bid result plus `p * 0.005 / n` ERG. There is
no term for winning a close race. This is arithmetic, not an estimate of `p`.
Neither the census nor the tests measure buyer demand. Seed recovery also assumes
that somebody submits a valid close and a producer includes it.

## Live UTXO footprint

These are full serialized box sizes from the reviewer's `RentAuctionEconomicsSpec`
run at byte price 360. They use one-token 114-byte sources, P2PK collector returns,
and 256-byte winning-recipient scripts. Different scripts and amounts change sizes.

| Box or transition | Bytes |
| --- | ---: |
| Source with one token | 114 |
| Fresh lot, one token | 1,162 |
| Fresh lot, 20 tokens | 1,865 |
| Fresh lot, 32 tokens | 2,309 |
| Bid successor with 256-byte recipient | Fresh lot + 260 |
| Collector return | 113 |
| Winner, one token | 369 |
| Winner, 32 tokens | 1,516 |
| Proceeds deposit | 893 |
| Native fee box | 147 |

A one-source auction expands live state while it is open. Bundling spreads the
contract and register overhead: the measured 20-token lot is smaller than its
20 separate 114-byte sources together. That comparison excludes beneficiary,
funding-change and fee outputs, so it is not a whole-transaction UTXO saving claim.

A no-bid close removes the auction and burns its tokens, but creates the collector
return and fee output. A sale creates a winner and deposit as well. A subsequent
merge removes the deposit and replaces the reserve. Expiry alone removes nothing;
untended lots and deposits remain live. Historical blocks are never deleted.

## Collector share and minimum bid

The 10% share gives the collector a reason to find bidders and to keep desirable
assets in suitable lots. It reduces reserve receipts and rebates self-bidding.
Ignoring rounding, fees and carrier differences, a collector that wins its own lot
pays 90% of its gross bid after the share returns. Its gross bidding capacity for
the same net outlay is `1 / 0.9`, about an 11.1% edge over an unrelated bidder.
This is not an automatic profit: the remaining bid still pays carrier, merge fee
and reserve proceeds.

The 0.05 ERG floor funds prescribed sale outputs even at byte price 10,000. It also
excludes gross bids below that floor. Tokens or lots valued at 0.005–0.05 ERG may
therefore burn unsold unless a bundle attracts a qualifying bid. A buyer also
receives the carrier ERG; face-value bid is not identical to net token expenditure.

Bundling can reduce costs while making buyers purchase unwanted assets together.
The collector can choose smaller lots, but each adds seed, allowance, state and
closing work. The node neither estimates value nor chooses the profitable partition.

For a successful sale, reserve growth on merge is exactly:

```
B - floor(B / 10) - carrier - MERGE_BUDGET
```

The merge fee goes to the including miner. After mainnet re-emission starts at
height 2,080,800, merging must follow that block's scheduled reserve withdrawal
and consume its successor. A public merge against the previous reserve conflicts.
This makes timely merging a producer integration task, not a private keeper bounty.

## Other proposals

This is a brief comparison of the proposal snapshots summarized in section 6 of
the checked economic review, measured 2026-09-27. It is not a claim that any proposal
has been activated, or a prediction of its market outcomes.

| Proposal | Treatment and economic distinction |
| --- | --- |
| EIP-0049 | Archives boxes for owner revival rather than paying a collector to take their tokens. |
| EIP-0051 | Adds a token-haircut refresh opportunity and owner grace; third parties decide if tokens cover their cost. |
| EIP-0052 | Addresses producer control and rent attestation; it does not itself require these auctions or reserve proceeds. |
| EIP-53 | Adds grace and reduced-cost recreation before full consumption; it changes when owner state is lost. |
| Parallel ergo-sdk PR #1 design | Bundles tokens, returns dust-sized deposits on no-bid cleanup and pays a 10% share; supports indexed batch settlement. |
| This implementation | Keeps producer attestation and beneficiary rent; returns seed, charges bid-funded costs and covenants net proceeds into reserve. |

EIP-0051 does not need a consensus price oracle. The parallel design helped motivate
bundling and collector returns; its timings, deposit sizing and re-emission path
are not normative here. Combined activation with another proposal needs separate
compatibility rules and tests.

## Limits

The census values box ERG only. It contains no token market prices, liquidity,
redemption guarantees or bid probabilities. The measurements use synthetic sources
and the PR's builders, not a live auction market. They do not price capital lockup,
operator work, discovery failures, censorship or reserve-merge delays. A collector
that does not receive the beneficiary payment has different economics.

These examples do not establish profitability for all source values or partitions.
Resource timing and the provisional close-cost calibration issue are recorded in
[REVIEW.md](REVIEW.md). Test evidence and artifact provenance are in
[VERIFICATION.md](VERIFICATION.md).
