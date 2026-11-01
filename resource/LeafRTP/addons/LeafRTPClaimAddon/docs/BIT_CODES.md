# LeafRTPClaimAddon BIT Codes

Generated from the registered BIT catalog. IDs and keys are stable; retired codes are not reused.


## `CLAIM`

| ID | Code | Severity | Tier | Subsystem | Message key | Remediation |
|---:|---|---|---|---|---|---|
| 1 | `CLAIM_PLUGIN_NOT_INTEGRATED` | WARNING | PBIT | claim-integrations | - | Enable the integration in addons/integrations.yml or use a bit.yml severity override if intentional. |
| 2 | `CLAIM_INTEGRATION_FAILED` | ERROR | PBIT | claim-integrations | - | Update the claim plugin or disable its integration until its API is compatible. |
| 3 | `CLAIM_INTEGRATION_INACTIVE` | ERROR | PBIT | claim-integrations | - | Reload RTP after enabling the claim plugin so its verifier can be registered. |
| 4 | `CLAIM_PLUGIN_ABSENT` | INFO | PBIT | claim-integrations | - | Install and enable the claim plugin, or turn off its integration flag if it is not used. |
