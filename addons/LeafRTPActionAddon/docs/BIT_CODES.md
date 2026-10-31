# LeafRTPActionAddon BIT Codes

Generated from the registered BIT catalog. IDs and keys are stable; retired codes are not reused.


## `ACTADDON`

| ID | Code | Severity | Tier | Subsystem | Message key | Remediation |
|---:|---|---|---|---|---|---|
| 1 | `ACTADDON_NOT_REGISTERED` | WARNING | PBIT | action-setup | - | Fix the action definition's ERROR findings so it can be registered. |
| 2 | `ACTADDON_COMMAND_REGISTER_FAILED` | ERROR | PBIT | action-setup | - | Resolve the command registration failure and reload the action addon. |
| 3 | `ACTADDON_TRIGGER_INACTIVE` | ERROR | PBIT | action-setup | - | Ensure the trigger is registered for its action and start the wave timer for batch triggers. |
| 4 | `ACTADDON_NAME_COLLISION` | ERROR | PBIT | action-setup | - | Give each action and command a unique name and alias. |
