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
returns the collector's seed. The bid pays a collector share, the winner's ERG
carrier and a reserve-merge fee budget. The remaining proceeds enter a covenant
that can only increase the existing EIP-27 reserve. Unbid lots burn their tokens
on close. Collections can bundle sources, and one transaction can close several lots.

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

Amounts are nanoERG; 1 ERG = 1,000,000,000 nanoERG. These values come from
`RentAuctionContracts.scala`. They are executable draft choices, not assigned
network policy. `S` denotes seed principal and `Q` denotes the close allowance.
There is no fixed `SEED` or `CARRIER` constant.

| Name | Value | Purpose |
| --- | ---: | --- |
| `WINDOW` | 720 blocks | Initial bidding period |
| `MAXIMUM_WINDOW` | 1,440 blocks | Absolute end measured from collection |
| `EXTENSION` | 30 blocks | Late-bid extension, bounded by the absolute end |
| `TOKENS_PER_LOT` | 32 | Maximum distinct token entries per lot |
| `MINIMUM_BID` | 50,000,000 | Fixed gross bid floor, 0.05 ERG |
| `INCREMENT` | 1,000,000 | Minimum improvement over the previous bid |
| `CLOSE_ALLOWANCE` (`Q`) | 2,000,000 | Separate refundable allowance for closing fees |
| `MAX_CLOSE_FEE` | 2,000,000 | Maximum total native fee per close transaction |
| `COLLECTOR_SHARE_DENOMINATOR` | 10 | Collector receives floor(bid / 10) on sale |
| `MAX_BYTE_PRICE` | 10,000 nanoERG/byte | Byte-price ceiling used for payout funding |
| `MAX_PARTY_BYTES` | 256 bytes | Maximum collector or winning-recipient script length |
| `PAYOUT_OVERHEAD_BYTES` | 128 bytes | Return/winner envelope excluding script and tokens |
| `TOKEN_ENTRY_BYTES` | 42 bytes | Per-token winner envelope |
| `MAX_DEPOSIT_BYTES` | 2,048 bytes | Maximum serialized proceeds deposit size |
| `MIN_DEPOSIT_VALUE` | 20,480,000 | Deposit floor: MAX_DEPOSIT_BYTES * MAX_BYTE_PRICE |
| `MAX_CLOSE_LOTS` | 32 | Maximum auction inputs in one close |
| `MERGE_BUDGET` | 1,000,000 | Portion of each deposit reserved for the native merge fee |
| `MAX_MERGE_DEPOSITS` | 10 | Maximum deposits in one reserve merge |
| `MAX_MERGE_FEE` | 10,000,000 | Merge fee ceiling, including any sponsor |

Native minimum-value, box-size, transaction and block checks still apply. Amounts
and aggregate token accounting must not overflow. The node uses `BigInt` where
sums or conservation comparisons require it; the baseline rent charge stays `Int`.

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

Funded rent inputs retain their baseline recreation obligations. Each requires a
separate recreation index in Short context variable 127. Those indices must be
unique and cannot name fresh lots.

For a fully consumed input, variable 127 is only the baseline rent witness: it
must be a Short naming an in-range output. It does not map that source to a lot.
Repeated indices are allowed for fully consumed inputs, including token-bearing
ones. The builder uses index zero for them.

Token accounting is transaction-wide:

1. Select fully consumed rent inputs with nonempty auctionable token lists, in
   transaction input order. Their collection commitment is Blake2b-256 of their
   concatenated raw box IDs. All fresh lots carry this same commitment in R4.
2. Treat every output with the exact auction tree as a fresh-lot candidate. No
   recreation index may overlap this set. Require every candidate to have the
   fresh state below, creation height `H`, and enough opening seed.
3. For each token ID, the sum across fresh lots must equal the sum across selected
   sources. Each lot holds 1–32 distinct IDs with positive Long quantities. Sources
   may be bundled or split across lots. Consensus does not prescribe a partition.
4. Non-rent funding inputs must be token-free. Outputs outside recreations and fresh
   lots must be token-free. This keeps collection tokens out of funding change and
   beneficiary payments. Baseline validation also enforces each funded recreation.
5. No fresh lot may exist without an auctionable rent source in the same transaction.
   Ordinary transactions cannot create new auctions. Auction successor rules apply
   only when an existing auction is spent through its contract.

The default builder aggregates by token ID, sorts IDs by their hexadecimal encoding,
and packs up to 32 entries per lot. An explicit partition may separate assets or
split a quantity across lots; the same exact totals and per-entry limits apply.

There is one exception to auctionable tokens: when native EIP-27 debt redemption
rules apply, the EIP-27 accounting token must be burned and its existing ERG
payment obligation met. It cannot be transferred into an auction. All other token
entries are auctioned. This exception does not exempt SigUSD, SigRSV, LP tokens or
NFTs merely because they represent financial claims. The node performs no valuation.

Fully consumed inputs with no auctionable tokens contribute no lot or commitment
entry. Their rent witness must still be in range. End-height addition must not
overflow Int.

## Auction state

Fresh lots and bid successors have exactly registers R4 through R9:

| Register | Type | Meaning |
| --- | --- | --- |
| R4 | `Coll[Byte]`, length 32 | Collection commitment, immutable across bids |
| R5 | `Coll[Int]`, length 2 | Current deadline, immutable absolute deadline |
| R6 | `Long` | Gross winning bid `B`, initially zero |
| R7 | `Coll[Byte]` | Winning recipient's serialized ErgoTree, initially empty |
| R8 | `Long` | Refundable seed principal `S`, immutable across bids |
| R9 | `Coll[Byte]` | Collector-return ErgoTree, immutable across bids |

Initial R5 is `[H + WINDOW, H + MAXIMUM_WINDOW]`. A lot's value is exactly
`S + Q + B`; the close allowance is not part of R8. Deadlines satisfy
`creationHeight <= end <= cap` and `cap - creationHeight <= MAXIMUM_WINDOW`.

The node requires canonical, parsed collector and nonempty winning-recipient
ErgoTrees of 1–256 bytes. For zero bids R7 must be empty; otherwise `B >= MINIMUM_BID`.
The contract checks lengths but does not establish canonical parsing. These checks
prevent malformed script encodings; they do not prove that the named party controls
or can satisfy the chosen script.

### Opening seed and size envelopes

Let `p` be active `minValuePerByte` and `L` the collector script's byte length.
The node requires, only at opening:

```
S >= max(MAX_BYTE_PRICE * (PAYOUT_OVERHEAD_BYTES + L),
         1,
         p * Ufresh - CLOSE_ALLOWANCE)
```

`Ufresh` is `RentAuctionRules.lotSizeBound(lot, successor = false)`. It serializes
an upper-bound full box with maximum-width value, seed, height, deadlines and output
index, while retaining the tokens and collector script. Its bid is zero and R7 is
empty. Native validation separately checks the actual opening output.

The builder chooses this minimum unless an explicit larger `seed` is supplied.
It funds `S + Q` separately from the rent credited to the producer's beneficiary.
The current byte-price component is not recalculated on bids or closes. The fixed
return-box floor remains a state invariant, so the principal can return even after
a vote raises the byte price to 10,000.

Every auction output must also fit a maximum-width successor envelope, including
a 256-byte recipient and maximum Long bid, within `ErgoBox.MaxBoxSize`. Return and
winner envelopes are `128 + scriptBytes` and `128 + scriptBytes + 42 * tokenCount`.
The node checks actual close payouts against them. `RentAuctionParameterSpec`
checks these envelopes with the native serializer, including maximum heights,
values, token amounts and output indices.

## Bidding

The auction must be input 0; every other input is token-free. Before its deadline,
output 0 must recreate the same auction, preserving collection commitment, seed,
collector script, tokens and absolute cap. Its creation height is `H`. The new bid is at least `MINIMUM_BID`,
strictly exceeds the previous bid, and improves it by at least `INCREMENT`.

The successor deadline is `min(cap, max(previousEnd, H + EXTENSION))`, evaluated
without overflow as in the reference script. A bid is invalid at or after the
current end, including at the absolute cap.

If the previous bid is nonzero, output 1 refunds **the full previous bid** to its
recorded recipient script, contains no tokens, has creation height `H`, and carries
the consumed auction's box ID in R4. Funding must cover the full new bid and transaction fee. The prior
bid cannot be used to underfund its replacement. Competing bids spend the same
UTXO; only the accepted chain of bids wins. The protocol cannot accept or compare
bids that were never included in the chain.

## Batch settlement and no-bid closure

At or after each lot's deadline, anyone may close 1–32 lots in one transaction.
All inputs must be auctions; there are no funding inputs. For each input the
spending context supplies:

| Variable | Type | Meaning |
| --- | --- | --- |
| 0 | `Int` | First payout output index `k` for this lot |
| 1 | `Long` | This lot's allocated close fee `f` |
| 2 | `Int` | Active byte price `p` |

The contract bounds variable 2 to `[0, MAX_BYTE_PRICE]`. **The contract alone does
not authenticate it.** `RentAuctionRules.validateClose` requires it to equal the
active `minValuePerByte`, including on no-bid closes.

Let `share = floor(B / COLLECTOR_SHARE_DENOMINATOR)` and, for a sold lot,
`C = max(1, p * (128 + recipientScriptBytes + 42 * tokenCount))`.
The auction contract and node enforce these exact payouts:

| Slot | Recipient | Value | Tokens |
| --- | --- | --- | --- |
| `k` | Collector script in R9 | `S + Q - f + share` | None |
| `k + 1`, sold only | Winning recipient in R7 | `C` | Exactly the lot's tokens |
| `k + 2`, sold only | Proceeds-deposit covenant | `B - share - C` | None |

Each payout is created at `H`. The node requires exactly R4, containing the consumed
auction box ID, as its additional registers. The contract checks the payout scripts,
amounts, tokens, height and tag. It references the deposit by Blake2b-256 hash;
the node requires the exact compiled deposit tree as well.

The node makes every lot's payout range disjoint, even when parties use the same
script or lots contain the same token ID. A no-bid lot claims only slot `k`. No
auction output may be recreated. Exactly one output remains outside all ranges:
a token-free native fee output, with no additional registers, created at `H`.
Its value `F` must satisfy `0 < F <= MAX_CLOSE_FEE` and native dust rules.

For zero-based input index `i` among `N` lots, require:

```
f_i = F / N + (if i < F % N then 1 else 0)
```

Division is integer division. The sum of allocations equals the one fee output.
There is no private closer payout. Unused allowance and the whole seed principal
return to the recorded collector, regardless of who submits the close.

For unsold lots, the absence of any other token-bearing output burns all their
quantities. Sold lots retain only the exact winning quantities. Burning and output
ownership rely on the node rules as well as the contracts. Bids fund the carrier,
collector share and deposit; the merge budget is later taken from that deposit.

## Guaranteed reserve deposit

The new covenant is distinct from the existing EIP-27 deposit script. A valid
deposit has no tokens, exactly R4 of 32 bytes, at least `MIN_DEPOSIT_VALUE`
nanoERG, and, when created, height `H` and at most `MAX_DEPOSIT_BYTES` serialized
bytes. On sale its value is exactly `B - floor(B / 10) - C`. The fixed minimum bid
covers the prescribed carrier, share and deposit floor at the byte-price ceiling.

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

The merge budget is the including miner's native fee. There is no merger payout.
Before scheduled re-emission, anyone can submit a merge whose budgets fund that
fee without contributing ERG. At byte price 10,000 a single budget is below fee-box
dust; batch deposits or supply a sponsor. A fee-free parent is not a public-mempool
workaround.

Settlement and reserve merge are separate transactions; miners can include them
in order in the same block. The guarantee is conditional on spending the deposit:
it cannot be withdrawn as ordinary current income. Consensus does not promise
that someone will submit its merge promptly.

From mainnet height 2,080,800, the scheduled reward spends the reserve. A public
merge built against the previous reserve conflicts with it, making merging a
producer task in practice. A miner taking that block's reward must order
its native reserve withdrawal **before** the proceeds merge and construct the merge
against the withdrawal's reserve successor. Merging first sets the reserve creation
height to `H`, which prevents a subsequent same-height native reward withdrawal.
The final block balance may be lower than its starting reserve when the scheduled
reward exceeds the deposit principal; that principal, after the merge budget,
still increases the balance relative to the reward withdrawal. The reference block test exercises this ordering.

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
+ 4 * (sum(serialized input box bytes) + sum(serialized output box bytes))
+ 200 * (number of token entries across all inputs and outputs)
+ 4000 * (number of auction-script inputs and outputs)
+ 2000 * (number of proceeds-deposit-script inputs and outputs)
+ 50 * number of inputs
+ 100 * number of outputs
```

Full output boxes, including transaction ID and output index, determine output
byte counts. Use Long accumulators. Charge this once in state validation, including
fee/emission transactions; candidate selection and mempool estimates include the
same charge. Native block cost and size limits still apply. The formula is a draft
resource charge, not a measured proof of worst-case CPU cost, and requires benchmark
review before activation. `RentAuctionEconomicsSpec` measures collections and
batch closes against plain transfers; [REVIEW.md](REVIEW.md) records a close-cost
calibration concern. No checkpoint may bypass activated checks.

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

Bundling shares one seed and one close allowance across many sources. Returning
seed principal removes the race to recover it. Charging sale costs to the bid stops
a sale from consuming the collector's principal. Batch closing shares a native fee
across lots. The deposit tree is compiled first; the auction embeds its hash rather
than the full tree, reducing serialized lot size.

The 10% share rewards finding buyers and gives collectors a reason to keep valuable
assets in separate lots. It also rebates a self-bidding collector: ignoring rounding,
fees and carrier differences, its bid costs 90% of face value. It can bid about
11.1% more for the same net outlay than an unrelated bidder. This is an explicit
incentive trade-off, not prevention of self-dealing.

The fixed 0.05 ERG minimum avoids a bid becoming unable to fund prescribed payouts
after a byte-price vote. Lots that attract only 0.005–0.05 ERG valuations can remain
unsold and burn; buyers may value bundles differently. The floor is not a market
valuation. A price-indexed minimum would also need rules for a price rise before
close, which this implementation does not add.

Collectors still lock capital and pay collection and closing fees. A single small
source can be unprofitable. Seed returns require an actual close; no deadline forces
one. [ECONOMICS.md](ECONOMICS.md) gives measured outcomes and states when the collector
also receives the producer's beneficiary payment. Consensus cannot force collection.

# Backwards compatibility

Before activation, node behavior is unchanged. After activation, some transactions
accepted previously are rejected: uncommitted claims, token seizure without auction,
shared funded recreation outputs, noncanonical or underfunded lots, aliased close
payouts, unauthenticated close byte prices, bypass of protected protocol boxes,
and transactions exceeding the adjusted cost limit. Other existing transaction and script rules are
not relaxed. The accounting-token exception preserves existing EIP-27 redemption.

This revision also replaces the earlier draft's per-source mapping, R4 source ID,
fixed seed/carrier and R4–R8 state. Schema-1 tooling and earlier draft contract trees
are incompatible with these draft identities; there is no migration mechanism for
private-network boxes created under those earlier trees. Activation remains unset.

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

Coverage includes legacy-rent regressions, transaction-wide bundling, funded-output
aliasing, disjoint batch payouts, seed returns across byte-price votes, token amounts,
full refunds, timing boundaries, invalid recipient encodings, reserve sweep attacks,
batched deposits, cost limits, ordered same-block transactions, persistent UTXO and
digest processing, rollback/reapply, JSON plans and operator index reorganizations.
Specific evidence and limitations are recorded in VERIFICATION.md.

# Security considerations

Contracts alone are not the full protocol. Node rules bind collection token totals,
producer payment, payout ownership, active byte price and canonical scripts. Without
those restrictions an unsold contract's return condition alone does not prove a burn.
A closer may choose any valid total fee up to the ceiling, so unused allowance is
not guaranteed. Seed principal remains protected. Disjoint output ranges prevent one payout from satisfying several lots or parties.
Serializer envelopes and native dust/size rules keep prescribed outputs structurally
spendable within the stated byte-price range; users must still choose scripts they
can satisfy. Raising that ceiling requires another review.

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
