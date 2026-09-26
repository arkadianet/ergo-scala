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
  "action": "collect",
  "sources": ["EXPIRED_BOX_ID"],
  "funding": ["MINER_TOKEN_FREE_FUNDING_BOX_ID"],
  "beneficiary": "MINER_RECIPIENT_ERGOTREE_HEX",
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
boxes and invokes the Scala builder. An optional integer `seed` sets each lot's
backing value; omitting it uses the conservative dust-aware default.

Inspect the plan before signing: source IDs, lots, token quantities, beneficiary,
fees, funding and change are explicit. Required variable 127 extensions survive
wallet signing. Rent goes to the mining candidate API, not ordinary broadcasting.
The header public key is optional for a conventional node and is the lender/custom
key when using the matching Lithos flow. The node may filter a submitted transaction;
check its inclusion proof and eventual confirmation.

Rebuild after a tip change. Required auction and recreation heights are exact.
Do not automatically use an old signed collection at a new height.

## Bid

```json
{
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

The builder creates the full prior-bid refund automatically. Bid and successor
deadlines are visible in the output registers. A competing spend or height change
can invalidate the plan; refresh the auction ID and rebuild. Bids, settlement and
reserve merges use ordinary mempool submission. `broadcast` checks the transaction
first, then submits it; passing either call is not confirmation.

## Settle or burn an expired lot

```json
{
  "action": "settle",
  "auction": "EXPIRED_AUCTION_BOX_ID",
  "funding": [],
  "closer": "CLOSER_RECIPIENT_ERGOTREE_HEX",
  "fee": 1000000
}
```

Use the same prepare/sign/broadcast commands. With a winning bid, the builder pays
the tokens and creates the dedicated proceeds deposit. With no bid, it burns all
lot tokens. Remaining seed pays the closer and fee. If dust requirements exceed
available seed, add token-free `funding` inputs explicitly.

For a one-pass keeper scan:

```
python tools/rent-auction/rent_auction.py --jar NODE.jar --db rent.sqlite settle-due --closer CLOSER_ERGOTREE_HEX
```

This only writes plans under `settlements/`. Add `--execute` to sign, check and
broadcast them. The worker synchronizes the confirmed index first and reports
per-lot failures. It does not automatically select extra wallet funding or bid.

## Merge proceeds into the reserve

```json
{
  "action": "merge",
  "reserve": "CURRENT_AUTHENTIC_RESERVE_BOX_ID",
  "deposits": ["AUCTION_PROCEEDS_DEPOSIT_ID"]
}
```

Prepare/sign/broadcast as above. The builder verifies reserve script/NFT and exact
principal movement. It supports 1–10 deposits and an optional token-free `sponsor`
box used entirely for extra fee funding within the native fee limit.

```
python tools/rent-auction/rent_auction.py --jar NODE.jar --db rent.sqlite merge-due
```

Without `--execute`, this writes the first batch's plan under `merges/`; later
batches depend on its new reserve output. With `--execute`, it submits successive
batches using the updated reserve output. A conflicting reward withdrawal or merge
can require refreshing the reserve and retrying. Confirm the reserve successor's
increased value to observe completed reserve funding.

Once native re-emission rewards begin, miners should put the scheduled reserve
withdrawal first and merge into its reserve successor. Reversing that order prevents
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

This atomically writes `HEIGHT.json` for the patched Lithos candidate source. It
checks that the plan still names the next height and that input boxes still exist.
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
auction/deposit type, bid and deadline. It does not report token prices.

`vectors/collect-request.json` and `collect-plan.json` are synthetic deterministic
Scala test examples, **not live UTXOs**. `mainnet-contracts.json` records full trees,
hashes, reserve NFT and draft parameters. Recompilation must reproduce the hashes
before the same draft contracts are used.
