# Submission notes and suggested descriptions

Author: [arkadianet](https://github.com/arkadianet). EIP number: unassigned.
The material below provides suggested descriptions for technical review submissions.

## EIP repository

Suggested title: **Draft: producer-attested storage-rent auctions funding the EIP-27 reserve**

Suggested description:

> This draft requires transferable tokens in fully consumed storage-rent boxes to
> enter bounded public auctions. Nodes verify bids, refunds, settlement and token
> disposition without estimating token prices. Winning bids enter a covenant that
> requires an exact increase of the existing re-emission reserve. Unbid lots may
> close by burning their tokens; the existing rent age remains unchanged.
>
> Ordinary rent pays a producer-committed beneficiary independent of the header
> public key. The reference package includes height-gated Scala node enforcement,
> contracts, funded builders, wallet support, CLI/indexer/keeper tooling, and a
> tested Lithos candidate-source adapter. Activation and EIP number are unassigned.
>
> Review is requested particularly for the provisional extension keys shared with
> EIP-0052, auction/seed incentives, additional validation cost, reserve transaction
> ordering, and compatibility with any separate EIP-48 activation. The comparison
> with EIP-0051 acknowledges that it also does not require consensus price discovery.
>
> The package records 184 selected passing tests and one pre-existing ignored test.
> It is a reference implementation for protocol review, not a live-network activation
> or an independent audit.

Copy `EIP-XXXX.md` into the EIP repository under the filename agreed with its
maintainers. Include the implementation URL when a review branch is published.
Until then, the supplied archive and patches are the reviewable implementation.
Do not label the proposal Implemented/Activated or assign an existing EIP number.

## Node implementation

Suggested title: **Add opt-in reference implementation for storage-rent auctions**

Suggested description:

> Fully consumed rent boxes can currently surrender their tokens without a public
> sale. This change adds disabled-by-default consensus restrictions requiring
> deterministic auction lots, a producer rent commitment/payment, and reserve-only
> handling of winning bids. UTXO and digest validation, candidate assembly, mempool
> policy and wallet signing follow the same activation setting.
>
> The implementation preserves current rent fee arithmetic and native EIP-27 debt
> redemption. It adds no new script version or assigned mainnet activation. CLI,
> indexer, transaction builders and reproduction instructions ship with the EIP.
>
> Validation: 128 selected Scala node/wallet tests, 17 Python tests and 39 tests in
> the patched pinned Lithos client. Persistent tests use synthetic UTXO snapshots
> and fake PoW. See `docs/rent-auction/verification.json` for exact commands and
> artifact identity.

The patch targets node commit `5528ef569a41ebccbc8658212e6ee3c97d990b96` in the
provided checkout. Rebase and reverify against the target upstream branch before
submitting a node PR if its base differs. Preserve the fixture database isolation
change in `ErgoWalletServiceSpec` or submit that test fix separately with its reason.

## Lithos implementation

Suggested title: **Add optional height-bound queue source for rent-auction collections**

Suggested description:

> Queue mode loads a signed, funded rent collection for the requested candidate
> height, applies existing bundle budgets, and preserves Lithos's collateral-lender
> header key and candidate transport. The patched node enforces auction mapping
> and the separately configured rent beneficiary. Omitting the queue setting retains
> the current legacy collector.
>
> Four new queue/admission tests and 35 existing rent tests pass at the pinned
> client revision. The adapter pays rent directly to the configured beneficiary;
> holding top-up accounting and automatic reserve-merge rebasing are outside this
> adapter. Live pool/lender qualification remains necessary before deployment.

Apply the separate patch at the pinned Lithos revision documented in its README.
