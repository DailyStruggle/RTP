package com.craftaro.ultimateclaims;

import com.craftaro.ultimateclaims.claim.ClaimManager;

/** Test stub of the UltimateClaims 2.x main class (public static getInstance, instance getClaimManager). */
public class UltimateClaims {
  private static UltimateClaims instance;
  private final ClaimManager claimManager = new ClaimManager();

  public static UltimateClaims getInstance() {
    return instance;
  }

  public static void setInstance(UltimateClaims value) {
    instance = value;
  }

  public ClaimManager getClaimManager() {
    return claimManager;
  }
}
