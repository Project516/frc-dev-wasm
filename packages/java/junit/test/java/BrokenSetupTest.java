import org.junit.jupiter.api.*;

class BrokenSetupTest {
  @BeforeAll
  static void setUp() {
    throw new IllegalStateException("setup broke");
  }

  @Test
  void never() {}
}
