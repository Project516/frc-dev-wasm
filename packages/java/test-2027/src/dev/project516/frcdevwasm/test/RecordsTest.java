package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.shim.lang.Records;
import java.lang.reflect.RecordComponent;

/** The record reflection that vendor jars use in the browser JVM matches the JDK's. */
public final class RecordsTest {
  private RecordsTest() {}

  record Sample(double x, String name, int[] counts) {
    static final int UNUSED = 1;
  }

  public static void run(Tests t) {
    t.test("record components match the JDK's", () -> {
      Tests.check(Records.isRecord(Sample.class), "a record is a record");
      Tests.check(!Records.isRecord(String.class), "a class is not a record");
      Tests.eq(null, Records.getRecordComponents(String.class), "a class has no components");
      RecordComponent[] ours = Records.getRecordComponents(Sample.class);
      RecordComponent[] jdk = Sample.class.getRecordComponents();
      Tests.eq(jdk.length, ours.length, "component count");
      for (int i = 0; i < jdk.length; i++) {
        Tests.eq(jdk[i].getName(), ours[i].getName(), "name " + i);
        Tests.eq(jdk[i].getType(), ours[i].getType(), "type " + i);
        Tests.eq(jdk[i].getAccessor(), ours[i].getAccessor(), "accessor " + i);
        Tests.eq(Sample.class, ours[i].getDeclaringRecord(), "declaring record " + i);
        Tests.eq(jdk[i].getGenericType(), ours[i].getGenericType(), "generic type " + i);
      }
      Tests.eq(2.5, ours[0].getAccessor().invoke(new Sample(2.5, "a", new int[0])), "the accessor reads the value");
    });
  }
}
