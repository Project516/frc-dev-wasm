package frc.robot;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BasicTest {
  @Test
  void passes() {
    assertEquals(2, 1 + 1);
  }

  @Test
  void fails() {
    assertEquals(3, 1 + 1, "math is hard");
  }

  @Test
  void throwsIllegal() {
    helper();
  }

  private void helper() {
    throw new IllegalStateException("boom");
  }

  @Test
  @Disabled("not today")
  void skipped() {}

  @Test
  void assumption() {
    assumeTrue(false);
  }

  @Test
  void prints() {
    System.out.println("hello out");
    System.out.println("second line");
    System.err.println("hello err");
  }

  @Test
  @DisplayName("has a display name")
  void named() {}

  @Test
  void withCause() {
    throw new RuntimeException("outer", new IllegalArgumentException("inner"));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void parameterized(int n) {
    assertTrue(n < 2);
  }

  @Nested
  class Inner {
    @Test
    void nestedPasses() {}

    @Test
    void nestedFails() {
      fail("nested");
    }
  }
}
