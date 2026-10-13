# Nkap Agent

An agent that works alongside [Nkap](https://github.com/Deval123/nkap), the open mobile
money gateway. It lives in its own repository so that nothing of it is merged into the
gateway and it can be removed without touching Nkap.

**Status: under construction.** There is no agent yet. This repository currently holds
the build, the working conventions, the CI, and a local demo stack with its seed
([`docs/demo-stack.md`](docs/demo-stack.md)).

## Build

Requires Java 21 and Maven.

```bash
mvn -B clean verify
```

## Working on it

See [`docs/conventions.md`](docs/conventions.md). The licence is Apache 2.0, see
[`LICENSE`](LICENSE).
