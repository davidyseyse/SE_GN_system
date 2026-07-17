package com.classroom.api.auth;

import com.classroom.api.contract.Role;
import com.classroom.api.http.HttpExchangeAdapter;
import java.util.Set;

public final class AuthorizationGuard {
  private final AuthTokenService authTokenService;

  public AuthorizationGuard(AuthTokenService authTokenService) {
    this.authTokenService = authTokenService;
  }

  public AuthContext requireRoles(HttpExchangeAdapter exchange, Role... allowedRoles) {
    String authorizationHeader = exchange.header("Authorization");
    if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
      throw new SecurityException("Missing bearer token");
    }

    AuthContext authContext;
    try {
      authContext = authTokenService.verify(authorizationHeader.substring("Bearer ".length()));
    } catch (IllegalArgumentException exception) {
      throw new SecurityException(exception.getMessage());
    }
    Set<Role> roleSet = Set.of(allowedRoles);
    if (!roleSet.contains(authContext.role())) {
      throw new SecurityException("User role is not permitted for this action");
    }

    return authContext;
  }
}
