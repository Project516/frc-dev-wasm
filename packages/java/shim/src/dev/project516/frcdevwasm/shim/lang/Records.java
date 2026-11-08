package dev.project516.frcdevwasm.shim.lang;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import sun.misc.Unsafe;

/**
 * Record reflection, which the browser JVM does not implement: Class.isRecord is always false and
 * getRecordComponents null. Vendor jars call these instead (NativeStubber). Only records extend
 * Record, and a record's instance fields are its components, in order.
 */
public final class Records {
  private static final Unsafe UNSAFE = unsafe();

  private Records() {}

  public static boolean isRecord(Class<?> c) {
    return c.getSuperclass() == Record.class;
  }

  public static RecordComponent[] getRecordComponents(Class<?> c) {
    if (!isRecord(c)) {
      return null;
    }
    List<RecordComponent> components = new ArrayList<>();
    try {
      for (Field field : c.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        RecordComponent component = (RecordComponent) UNSAFE.allocateInstance(RecordComponent.class);
        set(component, "clazz", c);
        set(component, "name", field.getName());
        set(component, "type", field.getType());
        set(component, "accessor", c.getDeclaredMethod(field.getName()));
        components.add(component);
      }
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot read the components of " + c.getName(), e);
    }
    return components.toArray(new RecordComponent[0]);
  }

  private static void set(RecordComponent component, String name, Object value)
      throws NoSuchFieldException {
    long offset = UNSAFE.objectFieldOffset(RecordComponent.class.getDeclaredField(name));
    UNSAFE.putObject(component, offset, value);
  }

  private static Unsafe unsafe() {
    try {
      Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      return (Unsafe) field.get(null);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
