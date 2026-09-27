# Submission notes and suggested descriptions

Author: [arkadianet](https://github.com/arkadianet). EIP number: unassigned.
The material below provides suggested descriptions for technical review submissions.

## EIP repository

Suggested title: **Draft: producer-attested storage-rent auctions funding the EIP-27 reserve**

Suggested description:

> This draft requires transferable tokens in fully consumed storage-rent boxes to
> enter bounded public auctions. Nodes verify bids, refunds, settlement and token
> disposition without estimating token prices. Collections bundle fully consumed
> sources into lots. Close returns seed principal and unused allowance to the
> recorded collector. The bid funds a collector share, token carrier and merge fee;
> the remaining proceeds must increase the existing re-emission reserve. Unbid lots
> burn their tokens on close. The existing rent age remains unchanged.
>
> Ordinary rent pays a producer-committed beneficiary independent of the header
> public key. The reference package includes height-gated Scala node enforcement,
> contracts, funded builders, wallet support, schema-2 CLI/indexer/batch-close tooling,
> and a tested Lithos candidate-source adapter. Activation and EIP number are unassigned.
>
> Review is requested particularly for the provisional extension keys shared with
> EIP-0052, the 0.05 ERG bid floor, 10% share and self-bidding edge, close allowance,
> provisional close-cost calibration, producer reserve-merge ordering, and
> compatibility with any separate EIP-48 activation. The comparison with EIP-0051
> acknowledges that it also does not require consensus price discovery.
>
> The verification total is 263 passed (Scala 158, Python 64, Lithos 41) and one
> pre-existing ignored test. The regenerated machine-readable report is authoritative
> for an artifact. This is a reference implementation, not activation or an
> independent audit.

Copy `EIP-XXXX.md` into the EIP repository under the filename agreed with its
maintainers. Include the implementation URL when a review branch is published.
Until then, the supplied archive and patches are the reviewable implementation.
Do not label the proposal Implemented/Activated or assign an existing EIP number.

## Node implementation

Suggested title: **Add opt-in reference implementation for storage-rent auctions**

Suggested description:

> Fully consumed rent boxes can currently surrender their tokens without a public
> sale. This change adds disabled-by-default consensus restrictions requiring
> transaction-wide token accounting, a producer rent commitment/payment, refundable
> seed and disjoint batch-close payouts. Net proceeds after explicit bid-funded
> costs enter a reserve-increase covenant. UTXO and digest validation, candidate
> assembly, mempool policy and wallet signing follow the same activation setting.
>
> Rent fee arithmetic is preserved, including `Int` wrapping and its disclosed size
> bands; a non-wrapping repair is a separate, non-soft-fork change. The accounting-token
> exception applies only with chain EIP-27 checks enabled, above its activation
> height, and while rule 123 is active. Disabling that rule makes the token auctionable.
> Beneficiary payments count only at the collection height; triggered redemption
> payments must also use that height. The builder refuses funded accounting-token
> sources when native redemption triggers. No new script version or mainnet activation
> is assigned. CLI, indexer, builders and reproduction instructions ship with the EIP.
>
> Reviewer-reported selected results: core 45 plus one ignored, root 60, prover 4,
> candidate/wallet 49, Python 64 and patched Lithos 41 (6 adapter + 35 existing rent
> tests): 263 passed, including 158 Scala tests. `ergoCore/test` and `ergoWallet/test`
> also pass on the
> CI matrix of Scala 2.11.12, 2.12.20 and 2.13.18. Persistent tests use synthetic
> UTXO snapshots and fake PoW. Regenerate `docs/rent-auction/verification.json` for
> exact commands and artifact identity; this description does not certify a stale report.
> See `ECONOMICS.md` for measured collection results and `REVIEW.md` for cost calibration.

The selected regressions cover payment dates, live rule-123 status, funded
accounting-token sources, exact wrap boundaries, byte price 0, protected-tree
lengths, each block's own extension and upcoming wallet signing parameters.
The CLI requires disabled-rule status; operator preparation defers at voting
boundaries. The auction remains 991 bytes (Blake2b-256 `3643df8a` prefix) and the
deposit 816 bytes, with unchanged hashes recorded in `VERIFICATION.md`.
Regenerated vectors add only `parameters.disabledRules: []` to the request and
`votingLength: 1024` to the manifest.

`verify.py` fingerprints submitted docs and vectors, checks that Lithos equals
its baseline plus the submitted patch, and requires every test group to run at
least one test. Regenerate evidence after documentation changes before packaging.

The patch targets node commit `5528ef569a41ebccbc8658212e6ee3c97d990b96` in the
provided checkout. Rebase and reverify against the target upstream branch before
submitting a node PR if its base differs. Preserve the fixture database isolation
change in `ErgoWalletServiceSpec` or submit that test fix separately with its reason.

## Lithos implementation

Suggested title: **Add optional height-bound queue source for rent-auction collections**

Suggested description:

> Queue mode loads one signed, funded schema-2 bundled collection for the requested
> candidate height, carries its collector register and repeated rent witnesses,
> applies existing bundle budgets, and preserves Lithos's collateral-lender
> header key and candidate transport. The patched node enforces auction mapping
> and the separately configured rent beneficiary. Omitting the queue setting retains
> the current legacy collector.
>
> The reviewer's clean run reports six adapter tests and 35 existing rent tests
> passing at the pinned client revision. The adapter pays rent directly to the
> configured beneficiary; holding top-up accounting and automatic reserve-merge rebasing are outside this
> adapter. Live pool/lender qualification remains necessary before deployment.

Apply the separate patch at the pinned Lithos revision documented in its README.
