# Build and operator guide

## Build and verify

Requirements: JDK 11, sbt (this node pins 1.11.1), Python 3.10 or newer, Git.
Python tooling uses the standard library only. Commands below run from the node
repository root. `python` means the Python 3 executable on your platform.

```
python tools/rent-auction/verify.py --assemble --lithos /path/to/patched/Lithos-Client
python tools/rent-auction/package.py --with-jar
```

If sbt is not discoverable, supply `--sbt-launcher /path/to/sbt-launch.jar` or set
`SBT_LAUNCH_JAR`. The verifier writes full logs under `target/rent-auction-verification/`
and a machine-readable result at `docs/rent-auction/verification.json`. To verify
only the node and Python tooling, omit `--lithos`; complete packaging requires the
Lithos verification too. The source package contains a separate Lithos patch.
Before sbt, verification deletes `target/rent-auction-vectors/`. `RentAuctionCliSpec`
must regenerate both collection files; missing files stop verification. With
`--assemble`, the standalone mainnet CLI plan must equal the test-generated plan.

The assembled JAR is under `target/scala-2.12/ergo-*.jar`. In the commands below,
replace `NODE.jar` with that exact path. The distribution archive optionally includes
the same verified JAR as `bin/ergo-rent-auction-reference.jar`.

## Private network configuration

The production default is `ergo.chain.rentAuctionActivationHeight = null`.
For an agreed private-network test, set the same positive activation height on
every validating node. Keep the network's existing protocol/script version.

```
ergo.chain.rentAuctionActivationHeight = 2100001
ergo.node.rentAuctionBeneficiaryHex = "YOUR_MINER_RECIPIENT_ERGOTREE_HEX"
```

The height above is an example, **not an assigned public-network activation**.
Use a parsed, canonical ErgoTree for the beneficiary. If unset, the node uses its
native delayed miner-reward script. With Lithos, set it explicitly to the miner's
chosen recipient so it does not depend on the collateral lender's header key.
Use the same recipient in collection requests. Use a UTXO mining node with its
authenticated mining API enabled; do not expose wallet/mining API keys publicly.

For a custom network, pass `--network config:/absolute/path/to/network.conf` to the
operator. That config must include the matching network parameters and reserve NFT.
The mainnet and testnet CLI profiles use their actual native reserve scripts/NFTs;
their availability does not enable the feature on those networks.

## Connection, manifests and amounts

Set `ERGO_API_KEY` in the environment for authenticated operations. API keys are
never put in saved transaction plans. Wallet signing uses the node wallet; the CLI
never asks for a mnemonic or private key. The wallet must be unlocked when it is
needed to sign owned funding inputs. Remote connections carrying an API key require
HTTPS; localhost HTTP is supported.

```
python tools/rent-auction/rent_auction.py --jar NODE.jar manifest
python tools/rent-auction/rent_auction.py --node http://127.0.0.1:9053 --db rent.sqlite sync
python tools/rent-auction/rent_auction.py --jar NODE.jar --db rent.sqlite rent-candidates
```

Global options (`--node`, `--jar`, `--network`, `--db`) go before the subcommand.
Amounts in JSON are integer nanoERG. Token quantities are integer Long values and
must not pass through a JavaScript floating-point conversion. Scripts are ErgoTree
hex, not Ergo addresses. Convert wallet addresses to their locking scripts before
building requests. Box IDs are 64 hexadecimal characters.

The indexer loads genesis boxes, walks canonical full blocks from height 1, tracks
live boxes and keeps transactional rollback records. It resumes after restart and
rolls back orphaned blocks. An archival node is needed for initial indexing. Use
`sync --through HEIGHT` for a bounded pass. Re-run after a reorganization; a chain
change mid-pass is detected before committing the affected block. Each network
needs a separate database, enforced using its genesis boxes.

The index is a discovery aid. Before building or signing, the CLI resolves inputs
again through `/utxo/withPool/byId`. A candidate listing is not proof that collection
is profitable or that native EIP-27 obligations can be met.

## Collect rent

Create `collect.json` using real unspent source and funding IDs:

```json
{
  "schemaVersion": 2,
  "action": "collect",
  "sources": ["EXPIRED_BOX_ID_A", "EXPIRED_BOX_ID_B", "EXPIRED_BOX_ID_C"],
  "funding": ["MINER_TOKEN_FREE_FUNDING_BOX_ID"],
  "beneficiary": "MINER_RECIPIENT_ERGOTREE_HEX",
  "collector": "COLLECTOR_RETURN_ERGOTREE_HEX",
  "change": "MINER_CHANGE_ERGOTREE_HEX",
  "fee": 1000000
}
```

```
python tools/rent-auction/rent_auction.py --jar NODE.jar prepare collect.json collect-plan.json
python tools/rent-auction/rent_auction.py sign collect-plan.json collect-signed.json
python tools/rent-auction/rent_auction.py candidate collect-signed.json --miner-pk HEADER_PUBLIC_KEY
```

The live wrapper supplies next-block height and current storage/dust parameters;
offline values in the request cannot override them. It resolves IDs into complete
boxes and invokes the Scala builder. Requests and plans use schema 2. `collector`
is required; it fixes the seed-return script in R9. It may equal the beneficiary,
but the producer's beneficiary commitment is unchanged.

The builder aggregates auctionable tokens across fully consumed sources, sorts by
token ID and packs up to 32 distinct entries per lot. The R4 commitment covers all
fully consumed auctionable-token source IDs in input order. Optional `lots` provides
an explicit partition, for example:

```json
"lots": [
  [{"tokenId": "TOKEN_ID_A", "amount": 100}],
  [{"tokenId": "TOKEN_ID_B", "amount": 100}, {"tokenId": "TOKEN_ID_C", "amount": 100}]
]
```

This is a request-field fragment; replace placeholders with actual IDs and amounts.
Totals must match the source tokens exactly. Each lot needs its own seed and allowance.
An optional integer `seed` sets R8 principal for every lot, subject to the node minimum;
it excludes the separate 2,000,000 nanoERG close allowance. Omitting `seed` lets the
builder size it from the serializer envelope and active byte price.

Inspect the plan before signing: source IDs, lots, token quantities, beneficiary,
collector, seed principal, allowance, fees, funding and change are explicit.
Variable 127 remains a serialized Short rent witness. Fully consumed inputs may
repeat its index; it no longer identifies a source's lot. Funded inputs retain
unique recreation indices. The wrapper checks that signing preserves input order
and extensions, including close variables 0/1/2 and rent variable 127. Rent goes to the mining candidate API, not ordinary broadcasting.
The header public key is optional for a conventional node and is the lender/custom
key when using the matching Lithos flow. The node may filter a submitted transaction;
check its inclusion proof and eventual confirmation.

Rebuild after a tip change. Required auction and recreation heights are exact.
Do not automatically use an old signed collection at a new height.

## Bid

```json
{
  "schemaVersion": 2,
  "action": "bid",
  "auction": "CURRENT_AUCTION_BOX_ID",
  "funding": ["BIDDER_TOKEN_FREE_FUNDING_BOX_ID"],
  "bid": 1000000000,
  "recipient": "BIDDER_RECIPIENT_ERGOTREE_HEX",
  "change": "BIDDER_CHANGE_ERGOTREE_HEX",
  "fee": 1000000
}
```

```
python tools/rent-auction/rent_auction.py --jar NODE.jar prepare bid.json bid-plan.json
python tools/rent-auction/rent_auction.py sign bid-plan.json bid-signed.json
python tools/rent-auction/rent_auction.py broadcast bid-signed.json
```

The minimum gross bid is 50,000,000 nanoERG and the minimum improvement is
1,000,000, as defined in `RentAuctionContracts.scala`. The builder creates the
full prior-bid refund automatically, dated at the current height. Bid and successor
deadlines are visible in the output registers. A competing spend or height change
can invalidate the plan; refresh the auction ID and rebuild. Bids and closes use
ordinary mempool submission. Reserve merges can use it when their reserve input
remains current; see the producer ordering requirement below. `broadcast` checks the transaction
first, then submits it; passing either call is not confirmation.

## Close expired lots

```json
{
  "schemaVersion": 2,
  "action": "close",
  "auctions": ["EXPIRED_AUCTION_BOX_ID_A", "EXPIRED_AUCTION_BOX_ID_B"],
  "fee": 1000000
}
```

Use the same prepare/sign/broadcast commands. All selected lots must be expired.
There are no funding inputs or `closer` field. The builder creates disjoint payout
ranges and context variables 0 (first output index, Int), 1 (allocated fee, Long)
and 2 (active byte price, Int). Sold lots get collector return, winner and deposit
outputs; unsold lots get only a collector return and burn their tokens. There is
one native fee output. The total fee is divided evenly, with remainder nanoERG
assigned to the earliest inputs. The seed and unused allowance return to R9.

For a one-pass scan:

```
python tools/rent-auction/rent_auction.py --jar NODE.jar --db rent.sqlite settle-due
```

The worker command retains the name `settle-due`; the transaction action is `close`.
There is no `--closer`. Without `--execute`, it writes plans under `settlements/`.
With `--execute`, it signs, checks and submits. It synchronizes the confirmed index,
sorts due IDs, and starts with batches of at most 32. It halves batches that exceed
its conservative JSON-byte or additional-cost budget. Options `--max-bytes` and
`--max-cost` lower the active block budgets; a quarter of the cost budget is reserved
for native validation. This is operator admission, not a consensus CPU bound.
The node's `/transactions/check` remains authoritative before submission.

Without `--fee`, live close preparation uses 1,000,000 nanoERG at byte prices up to
360 and 2,000,000 above that. The total close fee may not exceed 2,000,000. Fee
allocation comes from the allowances; it does not give the closer a bounty.

The workers rebuild for changed height or storage/dust parameters and remove spent
members before rebuilding the remaining batch. Repeated stale preparation is bounded
to three attempts. Plans are saved before submission. A failure after submission
may have an ambiguous outcome; the worker does not automatically retry that broadcast.
Check the saved transaction ID before retrying. Workers do not auto-bid or select
extra wallet funding.

## Fees at high byte prices

`RentAuctionEconomicsSpec` measures a native fee box of 147 bytes. At byte price
10,000 its dust is 1,470,000 nanoERG (0.00147 ERG), so a 0.001 ERG fee is invalid.
The measured high-price lifecycle uses 0.002 ERG per transaction. Explicit collect
and bid fees must also fund native dust; their default 0.001 ERG is not sufficient
at that ceiling. A larger lot seed does not fix a dust-valued fee output.

## Merge proceeds into the reserve

```json
{
  "schemaVersion": 2,
  "action": "merge",
  "reserve": "CURRENT_AUTHENTIC_RESERVE_BOX_ID",
  "deposits": ["AUCTION_PROCEEDS_DEPOSIT_ID"]
}
```

Prepare/sign/broadcast as above. The builder verifies reserve script/NFT and exact
principal movement. It supports 1–10 deposits and an optional token-free `sponsor`
box used entirely for extra fee funding within the 10,000,000 nanoERG limit. There
are exactly two outputs: increased reserve and native fee. Each deposit contributes
1,000,000 nanoERG to that fee; the rest must increase the reserve. No output pays
the submitter. Before re-emission, a merge funded by its budgets costs a volunteer
no ERG, although it pays no private reward.

```
python tools/rent-auction/rent_auction.py --jar NODE.jar --db rent.sqlite merge-due
```

Without `--execute`, this writes the first batch's plan under `merges/`; later
batches depend on its new reserve output. With `--execute`, it submits successive
batches using the updated reserve output. IDs are sorted and batches avoid leaving
a singleton when splitting into two funded batches can consume the same set.
At byte price 10,000 a singleton budget cannot fund fee dust. The worker reports
`unfunded`, the deposit IDs and their `mergeBudget`; wait for more deposits or
prepare a merge with an explicit sponsor. Other pending work is reported as
`deferred`, including batches waiting for a reserve successor. A spent reserve
requires syncing and rebuilding with the producer. Confirm the successor's
increased value to observe completed reserve funding.

From mainnet height 2,080,800, scheduled reward transactions spend the reserve.
A public merge against the previous reserve conflicts with that spend. Merging
is therefore a producer task in practice: put the scheduled reserve withdrawal
first and merge into its reserve successor. Reversing that order prevents
the same-height reward withdrawal because this draft refreshes reserve creation
height. Such a merge must be rebuilt for that successor; the generic keeper cannot
predict a private miner candidate. Offline builders accept the full successor box
JSON, and the candidate API accepts the ordered transaction array. The current
Lithos adapter does not automatically rebase public merges onto its reward bundle.

## Lithos

Follow [the adapter instructions](../../tools/rent-auction/lithos/README.md).
After preparing and signing one collection batch for the next height:

```
python tools/rent-auction/rent_auction.py lithos-queue collect-plan.json collect-signed.json /path/to/lithos-rent-queue
```

This atomically writes a schema-2 `HEIGHT.json` envelope with `height` and one
`transaction` for the patched Lithos candidate source. It checks that the plan still names the next height and that input boxes still exist.
It does not broadcast the claim. Lithos supplies its existing collateral-lender
header public key unchanged. Configure the node beneficiary and request beneficiary
identically. Queue mode replaces Lithos's legacy rent source; it does not run both.

The adapter reserves its whole configured rent-source cost budget for this batch
and conservatively counts JSON bytes for local admission. The node enforces actual
serialized size and execution cost. Set a reasonable source budget below the full
block limits. The adapter pays the configured recipient directly; it does not add
the payout to Lithos's holding capital accounting.

## Offline interface and example vectors

```
java -cp NODE.jar org.ergoplatform.tools.RentAuctionCli manifest mainnet contracts.json
java -cp NODE.jar org.ergoplatform.tools.RentAuctionCli prepare mainnet plan.json request.json
```

Offline requests supply full box JSON, exact `height`, and `parameters` containing
`storageFeeFactor` and `minValuePerByte`. The live wrapper normally supplies these.
The `inspect` action reports native serialized box size, wrapped charge, age,
auction/deposit type, bid, deadline, seed principal, collector and collection
commitment. It does not report token prices. The offline CLI defaults to a 0.001 ERG
fee; supply a spendable fee explicitly when using a higher byte price.

`vectors/collect-request.json` and `collect-plan.json` are synthetic deterministic
Scala test examples, **not live UTXOs**. `RentAuctionCliSpec` now generates four
fully consumed sources, two lots of two token entries, and a P2PK collector return.
After a code change, regenerate these files through the verifier before using them
as artifact evidence. `mainnet-contracts.json` records full trees, hashes, reserve NFT and draft parameters. Recompilation must reproduce the hashes
before the same draft contracts are used.
