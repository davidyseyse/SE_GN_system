package com.classroom.api.classroom;

import java.security.SecureRandom;

public final class ClassroomCodeGenerator {
  private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  public String generate() {
    char[] code = new char[6];
    for (int index = 0; index < code.length; index++) {
      code[index] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
    }
    return new String(code);
  }
}
