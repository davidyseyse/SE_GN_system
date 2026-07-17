package com.classroom.api.util;

import java.util.Base64;

public final class Base64Url {
  private Base64Url() {
  }

  public static String encode(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  public static byte[] decode(String value) {
    return Base64.getUrlDecoder().decode(value);
  }
}
