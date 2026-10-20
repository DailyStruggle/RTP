package com.griefdefender.api;

import com.griefdefender.api.claim.Claim;

/** Test stand-in of the GriefDefender {@code Core}: {@code getClaimAt(Object location)}, wilderness when unclaimed. */
public interface Core {
  Claim getClaimAt(Object location);
}
