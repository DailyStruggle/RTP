# Intentionally empty

This directory is empty by design. `lobby-a` is a Paper "lobby" server in the
devstack: it participates in the network (`network.yml` has `role: backend`
and a unique `serverId`) so it can dispatch `/rtp` to the *real* destination
backends (`backend-a`, `backend-b`) via the cross-server pipeline, but it has
no local destination region of its own.

Adding any `*.yml` here would give the lobby a local destination and defeat
the purpose of the lobby role in the devstack. To test the routed-only case,
keep this directory empty.

See `devstack/README.md` ("Lobbies") and `MULTI_SERVER_PLAN.md` for the
broader rationale.
