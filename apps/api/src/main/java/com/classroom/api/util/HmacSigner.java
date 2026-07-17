package com.classroom.api.util;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacSigner {
  private HmacSigner() {
  }

  public static String sign(String input, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      SecretKeySpec key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
      mac.init(key);
      byte[] digest = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
      return Base64Url.encode(digest);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to sign token", exception);
    }
  }
}
