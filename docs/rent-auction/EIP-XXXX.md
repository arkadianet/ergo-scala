# Producer-attested storage-rent auctions and reserve funding

* EIP: unassigned
* Author: [arkadianet](https://github.com/arkadianet)
* Status: Draft
* Type: Standards Track, Core
* Created: 26-Sep-2026
* License: CC0-1.0
* Forking: proposed soft fork; activation unassigned

# Abstract

After activation, a block producer collecting storage rent must auction transferable
tokens from fully consumed boxes. The consensus rules do not estimate token prices.
Anyone may bid ERG, replace a lower bid with a full refund, and close an expired
auction. Successful settlement transfers the tokens to the winning recipient and
places the entire winning bid in a covenant that can only increase the existing
EIP-27 reserve. Unbid lots can be closed by burning their tokens.

Existing rent age and fee arithmetic remain in force. There is no additional grace
period. Ordinary rent ERG must pay a destination committed to by the block producer.
That destination is independent of the header mining key, accommodating systems
such as Lithos where the header key can belong to a collateral lender.

# Motivation

Storage rent should make abandoned live UTXOs economical to remove. Tokens in them
may still have buyers: fungible assets, stablecoins, reserve tokens, LP tokens or
NFTs. Giving all of them to the collector or burning them immediately bypasses
price discovery. A bounded auction offers a market opportunity before destruction
and can turn successful sales into additional funding for future block rewards.

This proposal reduces or replaces **live UTXO state**. It does not delete historical
blocks. Auctions temporarily add state; successful sales necessarily retain owner
UTXOs. Timeout alone does not execute a transaction. Collection, bidding, closing
and reserve merging require active participants.

# Specification

## Activation and scope

Let `A` be the network activation height. At `H < A`, validation is unchanged.
At `H >= A`, apply all rules below in addition to existing Scala-node validation.
The reference implementation leaves `A` unset; its configuration switch is for
coordinated private-network testing, not unilateral mainnet activation.

No new block or ErgoTree version is introduced. Contract trees use version 0 and
existing operations. The additional restrictions and additional charged cost form
a soft-fork candidate relative to the pinned node baseline. This argument requires
review against the eventual activation release and any concurrently adopted EIPs.
It does not authorize raising the script version or disabling ordinary rules.

Ordinary owner-authorized spending remains possible. A transaction invokes this
proposal's rent rules when an input has an empty proof, is at least the existing
`Constants.StoragePeriod` old, and supplies a Short context variable 127 naming an
in-range output. Classification follows the Scala interpreter's rent entry path;
the existing interpreter must also accept the transaction.

The current `Int * Int` storage-fee multiplication, including wrapping, is preserved.
This draft does not implement EIP-48. A separate fee repair changes eligibility and
must be specified, activated and tested independently. The supplied collector skips
non-positive wrapped charges. Nodes still classify them using the baseline rules.

## Draft constants

Amounts below are nanoERG; 1 ERG = 1,000,000,000 nanoERG. These are proposed draft
values, subject to economic review before network assignment.

| Name | Value | Purpose |
| --- | ---: | --- |
| `WINDOW` | 720 blocks | Initial bidding period |
| `MAXIMUM_WINDOW` | 1,440 blocks | Absolute end measured from collection |
| `EXTENSION` | 30 blocks | Late-bid extension, bounded by the absolute end |
| `TOKENS_PER_LOT` | 32 | Maximum token entries per lot |
| `SEED` | 5,000,000 | Minimum non-bid ERG backing each auction |
| `MINIMUM_BID` | 5,000,000 | Minimum accepted bid |
| `INCREMENT` | 1,000,000 | Minimum improvement over the previous bid |
| `CARRIER` | 2,000,000 | Minimum ERG sent with winning tokens |
| `MERGE_BUDGET` | 1,000,000 | Fee allowance added separately to each deposit |

Native minimum box-value and maximum box-size checks still apply. Collectors must
fund enough seed for the actual token count and active byte-price parameters. The
builder defaults to the larger of `SEED` and three maximum-sized box carriers at
the current minimum value per byte. Bidders or closers may add token-free funding.

## Producer attestation and payment

Two provisional extension fields are used:

* `0x0300`: Blake2b-256 of the concatenation of rent-claim transaction IDs, in
  their order within the block; each ID is decoded to its 32 raw bytes.
* `0x0301`: Blake2b-256 of the designated beneficiary's serialized ErgoTree.

A block containing rent claims must have exactly one of each field. A block with
no rent claims must have neither. Missing, duplicate, extra or incorrect fields
invalidate the block. The normal header/extension commitment authenticates them.
Transactions need not occupy a special block position.

`0x0300` overlaps the EIP-0052 proposal. Allocation and combined semantics must be
agreed with its authors before assignment; these keys are not registered by this
draft. A future combined proposal should use one ordered attestation convention.

For each claim, credit all input ERG if fully consumed; otherwise credit
`max(0, input.value - recreation.value)`. Sum these credits using non-wrapping
arithmetic. Outputs whose script hash matches `0x0301`, excluding every required
recreation and auction output, must together pay at least the credited amount.

The producer may choose any beneficiary. Consensus cannot identify a human miner,
prove who constructed the transaction, or prohibit a miner voluntarily paying a
third party. It enforces the producing block's commitment and payment. Header key
equality is deliberately not required. The supplied node rejects rent claims from
ordinary mempool admission and accepts them through authenticated mining candidate
submission. This is admission policy; commitment and payment are consensus rules.

## Rent output mapping

Every funded rent input requires its own recreation output under existing rent
rules. No two recreation/auction obligations may use the same output index.

For a fully consumed input with auctionable tokens:

1. Keep token entries in their original serialized order.
2. Split them into consecutive chunks of at most `TOKENS_PER_LOT` entries.
3. Context variable 127 names the first output; subsequent chunks occupy the
   immediately following outputs.
4. Each output uses the exact auction tree, creation height `H`, its exact chunk
   of token IDs and quantities, and the initial registers below.
5. Its ERG value is at least `SEED`, paid separately from ordinary rent credited
   to the beneficiary. Native ERG conservation also applies.

There is one exception to auctionable tokens: when native EIP-27 debt redemption
rules apply, the EIP-27 accounting token must be burned and its existing ERG
payment obligation met. It cannot be transferred into an auction. All other token
entries are auctioned. This exception does not exempt SigUSD, SigRSV, LP tokens or
NFTs merely because they represent financial claims. The node performs no valuation.

Empty fully consumed inputs create no auction obligation. The original rent input
index must nevertheless be in range. End-height addition must not overflow Int.

## Auction state

| Register | Type | Meaning |
| --- | --- | --- |
| R4 | `Coll[Byte]` of length 32 | Original consumed source box ID |
| R5 | `Coll[Int]` of length 2 | Current deadline, immutable absolute deadline |
| R6 | `Long` | Current winning bid |
| R7 | `Coll[Byte]` | Winning recipient's serialized ErgoTree |
| R8 | `Long` | Non-bid seed backing the auction |

Initial R5 is `[H + WINDOW, H + MAXIMUM_WINDOW]`, R6 is zero, R7 is empty,
and R8 equals the auction's ERG value. Initial registers are exactly R4 through R8.
An auction's value must equal seed plus bid, it must contain 1–32 token entries,
and its seed must be at least `SEED`. Deadlines satisfy creationHeight <= end <= cap
and cap - creationHeight <= `MAXIMUM_WINDOW`.

Every auction output with a nonzero bid must contain a parsed, canonical recipient
ErgoTree of 1–256 bytes. This is a node rule as well as a builder check: the script
alone only checks the recipient byte length. It prevents malformed byte strings
from permanently stranding the winning output. It does not prove that the bidder
controls that script; choosing a spendable recipient remains the bidder's duty.

The node prohibits creating fresh auction outputs without a mapped rent source.
An ordinary auction spend may only put a successor auction at output 0. All newly
created auction states must pass the shape and recipient checks.

## Bidding

The auction must be input 0; every other input is token-free. Before its deadline,
output 0 must recreate the same auction, preserving source ID, seed, tokens and
absolute cap. Its creation height is `H`. The new bid is at least `MINIMUM_BID`,
strictly exceeds the previous bid, and improves it by at least `INCREMENT`.

The successor deadline is `min(cap, max(previousEnd, H + EXTENSION))`, evaluated
without overflow as in the reference script. A bid is invalid at or after the
current end, including at the absolute cap.

If the previous bid is nonzero, output 1 refunds **the full previous bid** to its
recorded recipient script, contains no tokens and carries the consumed auction's
box ID in R4. Funding must cover the full new bid and transaction fee. The prior
bid cannot be used to underfund its replacement. Competing bids spend the same
UTXO; only the accepted chain of bids wins. The protocol cannot accept or compare
bids that were never included in the chain.

## Settlement and no-bid closure

At or after the deadline, any participant may close the auction:

* With a bid, output 0 pays the recorded recipient, carries exactly all auction
  tokens, at least `CARRIER` ERG, and the consumed auction ID in R4. Output 1 uses
  the new proceeds-deposit tree, contains no tokens, is created at `H`, carries
  the same R4 tag, and has value at least `bid + MERGE_BUDGET`.
* Without a bid, every output is token-free and none uses the auction tree.
  Thus all lot tokens burn. Native ERG conservation still requires the seed to
  go somewhere; a closer may use it for its payment and transaction fees.

Successful settlement's remaining seed may fund fees or the closer. No part of the
winning bid is reserved for these purposes. If current minimum-value requirements
exceed the seed, the closer supplies token-free additional funding. It is not
guaranteed that doing so will be profitable.

## Guaranteed reserve deposit

The new covenant is distinct from the existing EIP-27 deposit script. A valid
deposit has no tokens, R4 of 32 bytes, at least `MINIMUM_BID + MERGE_BUDGET` ERG,
and, when created, height `H`.

To consume one or more such deposits:

1. Input 0 is the authentic reserve: the existing native reserve tree and its
   network-specific NFT in token position 0 with quantity 1.
2. Inputs include 1–10 deposits and at most one other token-free fee sponsor.
   There are exactly two outputs.
3. Output 0 has the same native reserve tree, only the authentic NFT, and height
   `H`. Its value is exactly the input reserve value plus
   `sum(deposit.value - MERGE_BUDGET)` and is strictly greater than the input value.
4. Output 1 is the native fee script, is token-free, and receives exactly
   `MERGE_BUDGET * depositCount + sponsorValue`, at most 10,000,000 nanoERG.

All native reserve and transaction rules also apply. Incidental extra tokens in
the reserve input are burned by this merge layout. No new reserve contract is
introduced, no scheduled reward is simultaneously withdrawn, and no principal is
counted twice when deposits are batched. Additional value voluntarily added to a
deposit beyond its budget also reaches the reserve.

Settlement and reserve merge are separate transactions; miners can include them
in order in the same block. The guarantee is conditional on spending the deposit:
it cannot be withdrawn as ordinary current income. Consensus does not promise
that someone will submit its merge promptly.

After scheduled re-emission starts, a miner taking that block's reward must order
its native reserve withdrawal **before** the proceeds merge and construct the merge
against the withdrawal's reserve successor. Merging first sets the reserve creation
height to `H`, which prevents a subsequent same-height native reward withdrawal.
The final block balance may be lower than its starting reserve when the scheduled
reward exceeds the deposit; the full deposit still increases the balance relative
to that reward withdrawal. The reference block test exercises this ordering.

## Protecting protocol boxes from the rent shortcut

A transaction containing a well-formed auction or proceeds deposit, or the native
reserve script with the authentic reserve NFT, may not contain any rent claims.
This prevents both direct rent bypass and mixing an unrelated rent input into an
auction operation to bypass intended paths. These contracts must execute normally
even if they have become rent-aged. Malformed imitations do not acquire this
immunity merely by copying the auction/deposit script.

## Additional validation cost

For every transaction after activation, add the following to ordinary validation
cost before executing the new consensus checks:

```
100
+ 2 * (sum(serialized input box bytes) + sum(serialized output box bytes))
+ 100 * (number of token entries across all inputs and outputs)
+ 2000 * (number of auction-script inputs and outputs)
+ 50 * number of inputs
```

Full output boxes, including transaction ID and output index, determine output
byte counts. Use Long accumulators. Charge this once in state validation, including
fee/emission transactions; candidate selection and mempool estimates include the
same charge. Native block cost and size limits still apply. The formula is a draft
resource charge, not a measured proof of worst-case CPU cost, and requires benchmark
review before activation. No checkpoint may bypass activated checks.

# Rationale and alternatives

The node verifies market rules, never an asset price, oracle, whitelist or token
name. No-bid means that no qualifying bid was included during the window; it does
not prove the tokens lacked value. Censorship, discovery failures and user mistakes
remain possible. The finite extension balances late bidding with bounded lifetime.

EIP-0051 also does not require the node to know market value: it proposes a token
haircut and third-party economic choice. This proposal differs in auctioning fully
consumed assets, omitting its additional grace period, and directing sale proceeds
to the reserve. It does not claim EIP-0051 is impossible because it needs an oracle.

PR #2577 and EIP-0052 address rent admission/producer attestation. This draft adds
mandatory token disposition, separate beneficiary payment, auction protection and
reserve funding. Its attestation field must be reconciled with EIP-0052.

Miners bear initial seed and collection fees. They receive ordinary rent ERG but
do not receive auction bids. Profitability can therefore be negative, especially
for small boxes or low-interest assets. The EIP does not pretend consensus can
force miners to collect them. Subsidies or alternative incentive parameters would
be separately reviewed policy changes.

# Backwards compatibility

Before activation, node behavior is unchanged. After activation, some transactions
accepted previously are rejected: uncommitted claims, token seizure without auction,
shared rent recreation outputs, bypass of protected protocol boxes, and transactions
exceeding the adjusted cost limit. Other existing transaction and script rules are
not relaxed. The accounting-token exception preserves existing EIP-27 redemption.

Old collectors must be upgraded. Old nodes would not enforce the new restrictions.
Network-wide adoption requires the usual Ergo review and activation process. This
draft reserves neither an EIP number nor a deployment date.

# Reference implementation and test cases

The package is based on Scala node commit
`5528ef569a41ebccbc8658212e6ee3c97d990b96`, Scala 2.12.20 and sigma-state 6.0.6.
The Scala node is normative; sigma-rust behavior is not used as consensus evidence.

See [README](README.md), [verification](VERIFICATION.md),
[operator guide](OPERATIONS.md), and [review notes](REVIEW.md).
The `.es` sources, compiled contract manifests, node implementation, transaction
builders, wallet changes, CLI, indexer and Lithos patch ship together.

Coverage includes legacy-rent regressions, output aliasing, splitting, token amounts,
full refunds, timing boundaries, invalid recipient encodings, reserve sweep attacks,
batched deposits, cost limits, ordered same-block transactions, persistent UTXO and
digest processing, rollback/reapply, JSON plans and operator index reorganizations.
Specific evidence and limitations are recorded in VERIFICATION.md.

# Security considerations

Economic review must consider miner censorship, bid ordering, insufficient collection
incentives, nuisance auctions, dust-price changes, reserve contention, keeper outages
and the permanent consequences of burning unbid assets. Historical token metadata
does not make an abandoned token economically worthless. A four-year inactivity
period is an eligibility rule, not proof of death, abandonment or lost keys.

Winning an LP token, reserve token or NFT does not guarantee redemption, liquidity,
continued application support or meaningful economic rights. No protocol price
claim is made. The design and constants require independent security review before
live-fund use. Mainnet activation is intentionally absent from this package.

# References

* [Scala reference node](https://github.com/ergoplatform/ergo)
* [EIP-27](https://github.com/ergoplatform/eips/blob/master/eip-0027.md)
* [EIP-51 proposal, PR #108](https://github.com/ergoplatform/eips/pull/108)
* [EIP-52 proposal, PR #109](https://github.com/ergoplatform/eips/pull/109)
* [Node rent admission proposal, PR #2577](https://github.com/ergoplatform/ergo/pull/2577)
* [Lithos client](https://github.com/Lithos-Protocol/Lithos-Client)

# Copyright

This EIP text is released under CC0-1.0. Code retains the licenses of its containing
repositories and included third-party components.
