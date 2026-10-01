# Handoff — publish phone-alerts.contract.json

**To:** nfa-platform agent / ingest authority maintainer  
**From:** nfa-notification-collector  
**Date:** 2026-10-01  

## Ask

Please publish a machine-readable phone ingest contract at:

`D:\github\nfa-platform\contracts\ingest\phone-alerts.contract.json`

Use the snapshot shape already mirrored in this repo:

`docs/contracts/phone-alerts.contract.snapshot.json`

## Why

The collector performs a daily contract sync so phone projection/rate/quarantine rules stay aligned with the live gateway without inventing a second format. Until the published file exists, the collector uses the bundled snapshot derived from `DATABASE.md` §10 and `scripts/ingest-gateway.ts`.

## Do not change from the collector repo

This repository will not edit nfa-platform. Authority remains DATABASE.md + ingest-gateway.ts.
