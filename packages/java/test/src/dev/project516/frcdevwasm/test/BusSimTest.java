package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.shim.hal.DMAJNIImpl;
import dev.project516.frcdevwasm.shim.hal.LEDJNIImpl;
import dev.project516.frcdevwasm.sim.BoolValue;
import dev.project516.frcdevwasm.sim.CanData;
import dev.project516.frcdevwasm.sim.DigitalPorts;
import dev.project516.frcdevwasm.sim.DoubleValue;
import dev.project516.frcdevwasm.sim.Handles;
import dev.project516.frcdevwasm.sim.InterruptSources;
import dev.project516.frcdevwasm.sim.SimTime;
import dev.project516.frcdevwasm.sim.Status;
import edu.wpi.first.hal.CANAPIJNI;
import edu.wpi.first.hal.CANData;
import edu.wpi.first.hal.CANStreamMessage;
import edu.wpi.first.hal.DMAJNI;
import edu.wpi.first.hal.DMAJNISample;
import edu.wpi.first.hal.DigitalGlitchFilterJNI;
import edu.wpi.first.hal.HAL;
import edu.wpi.first.hal.InterruptJNI;
import edu.wpi.first.hal.LEDJNI;
import edu.wpi.first.hal.SPIJNI;
import edu.wpi.first.hal.can.CANInvalidBufferException;
import edu.wpi.first.hal.can.CANJNI;
import edu.wpi.first.hal.can.CANMessageNotAllowedException;
import edu.wpi.first.hal.can.CANMessageNotFoundException;
import edu.wpi.first.hal.can.CANStatus;
import edu.wpi.first.hal.can.CANStreamOverflowException;
import edu.wpi.first.hal.simulation.NotifyCallback;
import edu.wpi.first.hal.util.AllocationException;
import edu.wpi.first.hal.util.HalHandleException;
import edu.wpi.first.hal.util.UncleanStatusException;
import edu.wpi.first.wpilibj.CAN;
import edu.wpi.first.wpilibj.I2C;
import edu.wpi.first.wpilibj.SPI;
import edu.wpi.first.wpilibj.SerialPort;
import edu.wpi.first.wpilibj.simulation.CallbackStore;
import edu.wpi.first.wpilibj.simulation.I2CSim;
import edu.wpi.first.wpilibj.simulation.SPISim;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** CAN, SPI, I2C, serial, interrupts, DMA, glitch filters and radio LED through the HAL wrappers. */
public final class BusSimTest {
  private BusSimTest() {}

  private static ByteBuffer direct(int size) {
    return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
  }

  private static void expect(Class<? extends Throwable> type, Tests.Case c) throws Exception {
    try {
      c.run();
    } catch (Throwable t) {
      if (type.isInstance(t)) {
        return;
      }
      throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
    }
    throw new AssertionError("expected " + type.getSimpleName());
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public static void run(Tests t) {
    HAL.initialize(500, 0);
    canBus(t);
    canApi(t);
    spi(t);
    i2c(t);
    serial(t);
    interrupts(t);
    misc(t);
  }

  private static void canBus(Tests t) {
    t.test("can status is zero without a simulator and comes from the callback", () -> {
      CanData.reset();
      CANStatus s = new CANStatus();
      CANJNI.getCANStatus(s);
      Tests.eq(0.0, s.percentBusUtilization, "idle");
      CanData.getCanStatus.register(
          (name, st) -> {
            Tests.eq("GetCanStatus", name, "callback name");
            st.percentBusUtilization = 0.5f;
            st.busOffCount = 1;
            st.txFullCount = 2;
            st.receiveErrorCount = 3;
            st.transmitErrorCount = 4;
          });
      CANJNI.getCANStatus(s);
      Tests.eq(0.5, s.percentBusUtilization, "utilization");
      Tests.eq(1, s.busOffCount, "bus off");
      Tests.eq(2, s.txFullCount, "tx full");
      Tests.eq(3, s.receiveErrorCount, "rx errors");
      Tests.eq(4, s.transmitErrorCount, "tx errors");
      CanData.reset();
    });

    t.test("can send reaches callbacks and status maps to exceptions", () -> {
      CanData.reset();
      CANJNI.FRCNetCommCANSessionMuxSendMessage(0x1234, new byte[] {1, 2, 3}, 20);
      List<String> seen = new ArrayList<>();
      AtomicInteger code = new AtomicInteger();
      CanData.sendMessage.register(
          (name, id, data, size, period, status) -> {
            seen.add(name + ":" + id + ":" + Arrays.toString(Arrays.copyOf(data, size)) + ":" + period);
            status[0] = code.get();
          });
      CANJNI.FRCNetCommCANSessionMuxSendMessage(0x1234, new byte[] {1, 2, 3}, 20);
      Tests.eq("SendMessage:4660:[1, 2, 3]:20", seen.get(0), "send");
      code.set(Status.ERR_CAN_NOT_ALLOWED);
      expect(CANMessageNotAllowedException.class, () ->
          CANJNI.FRCNetCommCANSessionMuxSendMessage(1, new byte[0], 0));
      code.set(Status.ERR_CAN_INVALID_BUFFER);
      expect(CANInvalidBufferException.class, () ->
          CANJNI.FRCNetCommCANSessionMuxSendMessage(1, new byte[0], 0));
      code.set(Status.HAL_CAN_BUFFER_OVERRUN);
      expect(UncleanStatusException.class, () ->
          CANJNI.FRCNetCommCANSessionMuxSendMessage(1, new byte[0], 0));
      CanData.reset();
    });

    t.test("can receive finds no message until a callback provides one", () -> {
      CanData.reset();
      IntBuffer id = direct(4).asIntBuffer();
      ByteBuffer ts = direct(4);
      id.put(0, 0x42);
      expect(CANMessageNotFoundException.class, () ->
          CANJNI.FRCNetCommCANSessionMuxReceiveMessage(id, 0x1FFFFFFF, ts));
      CanData.receiveMessage.register(
          (name, msgId, mask, data, size, stamp, status) -> {
            Tests.eq(0x42, msgId[0], "requested id");
            Tests.eq(0x1FFFFFFF, mask, "mask");
            data[0] = 9;
            data[1] = 8;
            size[0] = 2;
            stamp[0] = 777;
          });
      byte[] got = CANJNI.FRCNetCommCANSessionMuxReceiveMessage(id, 0x1FFFFFFF, ts);
      Tests.check(Arrays.equals(new byte[] {9, 8}, got), "data " + Arrays.toString(got));
      Tests.eq(777, ts.getInt(0), "timestamp");
      CanData.reset();
    });

    t.test("can stream sessions: open, read, overrun", () -> {
      CanData.reset();
      CanData.openStreamSession.register(
          (name, handle, id, mask, max, status) -> {
            Tests.eq(5, max, "max messages");
            handle[0] = 77;
          });
      int session = CANJNI.openCANStreamSession(0x10, 0x1F, 5);
      Tests.eq(77, session, "session");

      AtomicInteger closed = new AtomicInteger();
      CanData.closeStreamSession.register((name, h) -> closed.set(h));
      CANJNI.closeCANStreamSession(session);
      Tests.eq(77, closed.get(), "closed");

      CANStreamMessage[] msgs = new CANStreamMessage[3];
      Tests.eq(0, CANJNI.readCANStreamSession(session, msgs, 3), "no reader");

      AtomicInteger status = new AtomicInteger();
      CanData.readStreamSession.register(
          (name, h, out, toRead, read, st) -> {
            out[0].messageId = 0x55;
            out[0].timeStamp = -1;
            out[0].dataSize = 2;
            out[0].data[0] = 5;
            out[0].data[1] = 6;
            read[0] = 1;
            st[0] = status.get();
          });
      Tests.eq(1, CANJNI.readCANStreamSession(session, msgs, 3), "one message");
      Tests.eq(0x55, msgs[0].messageID, "message id");
      Tests.eq(2, msgs[0].length, "length");
      Tests.eq(0xFFFFFFFFL, msgs[0].timestamp, "timestamp is unsigned");
      Tests.eq((byte) 6, msgs[0].data[1], "data");

      status.set(CanData.SESSION_OVERRUN);
      try {
        CANJNI.readCANStreamSession(session, msgs, 3);
        throw new AssertionError("expected overflow");
      } catch (CANStreamOverflowException e) {
        Tests.eq(1, e.getMessagesRead(), "messages read before overflow");
      }
      status.set(Status.ERR_CAN_NOT_INITIALIZED);
      expect(UncleanStatusException.class, () ->
          CANJNI.readCANStreamSession(session, msgs, 3));
      CanData.reset();
    });
  }

  private static int apiId(int manufacturer, int deviceType, int deviceId, int api) {
    return (deviceType & 0x1F) << 24 | (manufacturer & 0xFF) << 16 | (api & 0x3FF) << 6 | deviceId;
  }

  private static void canApi(Tests t) {
    t.test("can api packets carry the arbitration id and repeat periods", () -> {
      CanData.reset();
      List<int[]> sent = new ArrayList<>();
      CanData.sendMessage.register(
          (name, id, data, size, period, status) -> sent.add(new int[] {id, size, period}));
      try (CAN can = new CAN(3, 8, 10)) {
        can.writePacket(new byte[] {1, 2}, 5);
        can.writePacketRepeating(new byte[] {1}, 6, 50);
        can.writeRTRFrame(4, 7);
        can.stopPacketRepeating(6);
        Tests.eq(apiId(8, 10, 3, 5), sent.get(0)[0], "plain id");
        Tests.eq(2, sent.get(0)[1], "size");
        Tests.eq(CanData.SEND_PERIOD_NO_REPEAT, sent.get(0)[2], "period");
        Tests.eq(50, sent.get(1)[2], "repeat period");
        Tests.eq(apiId(8, 10, 3, 7) | CanData.IS_FRAME_REMOTE, sent.get(2)[0], "remote id");
        Tests.eq(4, sent.get(2)[1], "rtr length");
        Tests.eq(CanData.SEND_PERIOD_STOP_REPEATING, sent.get(3)[2], "stop");
        sent.clear();
      }
      Tests.eq(3, sent.size(), "close stops every api that was written");
      for (int[] m : sent) {
        Tests.eq(CanData.SEND_PERIOD_STOP_REPEATING, m[2], "close stop");
      }
      CanData.reset();
    });

    t.test("can api handle errors", () -> {
      int bad = Handles.make(40, Handles.CAN);
      Tests.eq(Status.HAL_HANDLE_ERROR, CANAPIJNI.writeCANPacketNoThrow(bad, new byte[1], 1), "no throw");
      expect(HalHandleException.class, () -> CANAPIJNI.writeCANPacket(bad, new byte[1], 1));
      expect(HalHandleException.class, () -> CANAPIJNI.readCANPacketNew(bad, 1, new CANData()));
      CANAPIJNI.cleanCAN(bad);
    });

    t.test("can api reads: new, latest and timeout", () -> {
      CanData.reset();
      AtomicInteger present = new AtomicInteger(1);
      AtomicLong stamp = new AtomicLong();
      CanData.receiveMessage.register(
          (name, id, mask, data, size, ts, status) -> {
            if (present.get() == 0) {
              return;
            }
            data[0] = 11;
            data[1] = 22;
            size[0] = 2;
            ts[0] = (int) stamp.get();
          });
      try (CAN can = new CAN(1)) {
        CANData d = new CANData();
        stamp.set(CAN.getTimestampBaseTime());
        Tests.check(can.readPacketNew(9, d), "new packet");
        Tests.eq(2, d.length, "length");
        Tests.eq((byte) 22, d.data[1], "data");
        Tests.eq(stamp.get(), d.timestamp, "timestamp");

        present.set(0);
        Tests.check(!can.readPacketNew(9, d), "no new packet");
        CANData latest = new CANData();
        Tests.check(can.readPacketLatest(9, latest), "cached latest");
        Tests.eq(2, latest.length, "cached length");
        Tests.eq((byte) 11, latest.data[0], "cached data");
        Tests.check(!can.readPacketLatest(10, new CANData()), "other api has nothing");
        Tests.check(can.readPacketTimeout(9, 1000, new CANData()), "still fresh");
        SimTime.advance(2_000_000);
        Tests.check(!can.readPacketTimeout(9, 1000, new CANData()), "timed out");
        Tests.check(can.readPacketLatest(9, new CANData()), "latest ignores age");
      } finally {
        SimTime.restart();
        CanData.reset();
      }
    });
  }

  private static void spi(Tests t) {
    t.test("spi port initialization shows in the sim store", () -> {
      SPISim sim = new SPISim();
      sim.resetData();
      AtomicInteger calls = new AtomicInteger();
      try (CallbackStore cb = sim.registerInitializedCallback((n, v) -> calls.incrementAndGet(), false);
          SPI spi = new SPI(SPI.Port.kOnboardCS0)) {
        Tests.check(sim.getInitialized(), "initialized");
        Tests.eq(1, calls.get(), "initialized callback");
      }
      Tests.check(!sim.getInitialized(), "closed");
    });

    t.test("spi write, read and transaction call the sim buffer callbacks", () -> {
      SPISim sim = new SPISim();
      sim.resetData();
      List<String> writes = new ArrayList<>();
      List<String> names = new ArrayList<>();
      try (CallbackStore w =
              sim.registerWriteCallback((n, buf, count) -> writes.add(n + Arrays.toString(buf) + count));
          CallbackStore r =
              sim.registerReadCallback(
                  (n, buf, count) -> {
                    names.add(n);
                    for (int i = 0; i < count; i++) {
                      buf[i] = (byte) (0x40 + i);
                    }
                  });
          SPI spi = new SPI(SPI.Port.kOnboardCS0)) {
        Tests.eq(3, spi.write(new byte[] {1, 2, 3}, 3), "write count");
        Tests.eq("Write[1, 2, 3]3", writes.get(0), "write callback");

        byte[] got = new byte[4];
        Tests.eq(4, spi.read(false, got, 4), "read count");
        Tests.check(Arrays.equals(new byte[] {0x40, 0x41, 0x42, 0x43}, got), "read data");

        Tests.eq(2, spi.read(true, got, 2), "initiated read");
        Tests.eq("Write[0, 0]2", writes.get(1), "initiate sends zeros");

        byte[] recv = new byte[3];
        Tests.eq(3, spi.transaction(new byte[] {7, 8, 9}, recv, 3), "transaction");
        Tests.eq("Write[7, 8, 9]3", writes.get(2), "transaction write");
        Tests.check(Arrays.equals(new byte[] {0x40, 0x41, 0x42}, recv), "transaction read");

        ByteBuffer send = direct(2);
        send.put(0, (byte) 5).put(1, (byte) 6);
        ByteBuffer in = direct(2);
        Tests.eq(2, spi.transaction(send, in, 2), "direct transaction");
        Tests.eq("Write[5, 6]2", writes.get(3), "direct write");
        Tests.eq((byte) 0x41, in.get(1), "direct read");
        ByteBuffer rd = direct(2);
        spi.read(false, rd, 2);
        Tests.eq((byte) 0x40, rd.get(0), "direct read only");
        Tests.eq("Read", names.get(0), "read callback name");
      }
    });

    t.test("spi sim callbacks modify the buffer in order and cancel", () -> {
      SPISim sim = new SPISim();
      sim.resetData();
      CallbackStore first = sim.registerReadCallback((n, buf, c) -> buf[0] = 1);
      CallbackStore second = sim.registerReadCallback((n, buf, c) -> buf[0] = (byte) (buf[0] + 10));
      try (SPI spi = new SPI(SPI.Port.kOnboardCS0)) {
        byte[] got = new byte[1];
        spi.read(false, got, 1);
        Tests.eq((byte) 11, got[0], "both ran in order");
        second.close();
        spi.read(false, got, 1);
        Tests.eq((byte) 1, got[0], "second cancelled");
        sim.resetData();
        got[0] = 99;
        spi.read(false, got, 1);
        Tests.eq((byte) 0, got[0], "reset drops callbacks");
      } finally {
        first.close();
      }
    });

    t.test("spi auto receive buffer callback", () -> {
      SPISim sim = new SPISim();
      sim.resetData();
      List<String> names = new ArrayList<>();
      try (CallbackStore cb =
              sim.registerReadAutoReceiveBufferCallback(
                  (name, buffer, numToRead) -> {
                    names.add(name);
                    int n = Math.min(numToRead, 3);
                    for (int i = 0; i < n; i++) {
                      buffer[i] = 100 + i;
                    }
                    return n;
                  });
          SPI spi = new SPI(SPI.Port.kOnboardCS0)) {
        spi.initAuto(100);
        spi.startAutoRate(0.01);
        int[] words = new int[5];
        Tests.eq(3, spi.readAutoReceivedData(words, 5, 0.1), "count");
        Tests.check(Arrays.equals(new int[] {100, 101, 102, 0, 0}, words), Arrays.toString(words));
        ByteBuffer buf = direct(20);
        Tests.eq(3, spi.readAutoReceivedData(buf, 5, 0.1), "direct count");
        Tests.eq(101, buf.order(ByteOrder.nativeOrder()).getInt(4), "direct data");
        Tests.eq(0, spi.getAutoDroppedCount(), "dropped");
        Tests.eq("AutoReceive", names.get(0), "callback name");
        spi.stopAuto();
        spi.freeAuto();
        sim.resetData();
        Tests.eq(0, spi.readAutoReceivedData(new int[2], 2, 0), "no callback reads nothing");
      }
    });

    t.test("spi jni argument checks and fixed values", () -> {
      expect(IllegalArgumentException.class, () -> SPIJNI.spiReadB(0, false, new byte[1], -1));
      expect(IllegalArgumentException.class, () -> SPIJNI.spiTransactionB(0, new byte[1], new byte[1], -1));
      expect(IllegalArgumentException.class, () -> SPIJNI.spiReadAutoReceivedData(0, new int[1], -1, 0));
      SPIJNI.spiSetMode(0, 3);
      Tests.eq(0, SPIJNI.spiGetMode(0), "mode is always 0 in the sim");
    });
  }

  private static void i2c(Tests t) {
    t.test("i2c write, read and transaction call the sim buffer callbacks", () -> {
      I2CSim sim = new I2CSim(0);
      sim.resetData();
      List<String> writes = new ArrayList<>();
      try (CallbackStore w = sim.registerWriteCallback((n, buf, c) -> writes.add(n + Arrays.toString(buf) + c));
          CallbackStore r =
              sim.registerReadCallback(
                  (n, buf, c) -> {
                    for (int i = 0; i < c; i++) {
                      buf[i] = (byte) (0x10 + i);
                    }
                  });
          I2C i2c = new I2C(I2C.Port.kOnboard, 0x1D)) {
        Tests.check(sim.getInitialized(), "initialized");
        Tests.check(!i2c.writeBulk(new byte[] {4, 5}), "write succeeds");
        Tests.eq("Write[4, 5]2", writes.get(0), "write callback");
        byte[] got = new byte[3];
        Tests.check(!i2c.readOnly(got, 3), "read succeeds");
        Tests.check(Arrays.equals(new byte[] {0x10, 0x11, 0x12}, got), "read data");
        byte[] recv = new byte[2];
        Tests.check(!i2c.transaction(new byte[] {1}, 1, recv, 2), "transaction succeeds");
        Tests.eq("Write[1]1", writes.get(1), "transaction write");
        Tests.check(Arrays.equals(new byte[] {0x10, 0x11}, recv), "transaction read");
        Tests.check(!i2c.write(2, 3), "register write");
        Tests.eq("Write[2, 3]2", writes.get(2), "register bytes");
        ByteBuffer direct = direct(2);
        Tests.check(!i2c.read(7, 2, direct), "register read into a direct buffer");
        Tests.eq((byte) 0x11, direct.get(1), "direct data");
        Tests.eq("Write[7]1", writes.get(3), "read writes the register");
      }
      Tests.check(!sim.getInitialized(), "closed");
    });

    t.test("i2c ports are independent", () -> {
      I2CSim mxp = new I2CSim(1);
      mxp.resetData();
      AtomicInteger calls = new AtomicInteger();
      try (CallbackStore cb = mxp.registerWriteCallback((n, b, c) -> calls.incrementAndGet());
          I2C onboard = new I2C(I2C.Port.kOnboard, 1);
          I2C other = new I2C(I2C.Port.kMXP, 2)) {
        onboard.writeBulk(new byte[] {1});
        Tests.eq(0, calls.get(), "onboard write not seen on mxp");
        other.writeBulk(new byte[] {1});
        Tests.eq(1, calls.get(), "mxp write seen");
      }
    });
  }

  private static void serial(Tests t) {
    t.test("serial port is a stub that reads zeros", () -> {
      try (SerialPort port = new SerialPort(9600, SerialPort.Port.kOnboard)) {
        port.setTimeout(0.1);
        port.enableTermination();
        port.disableTermination();
        port.setReadBufferSize(64);
        Tests.eq(0, port.getBytesReceived(), "bytes received");
        Tests.eq(0, port.write(new byte[] {1, 2, 3}, 3), "write count");
        Tests.eq(0, port.read(4).length, "read returns nothing");
        port.flush();
        port.reset();
      }
      byte[] buf = {5, 5, 5, 5};
      Tests.eq(0, edu.wpi.first.hal.SerialPortJNI.serialRead(0, buf, 3), "jni read");
      Tests.check(Arrays.equals(new byte[] {0, 0, 0, 5}, buf), "jni read zero fills");
      Tests.eq(0, edu.wpi.first.hal.SerialPortJNI.serialInitializePortDirect((byte) 0, "x"), "direct port");
    });
  }

  private static InterruptSources.Provider provider(BoolValue[] dio, DoubleValue[] analog) {
    return new InterruptSources.Provider() {
      public BoolValue digitalInput(int channel) {
        return dio[channel];
      }

      public int analogTriggerInputIndex(int triggerHandle) {
        return triggerHandle & 0xffff;
      }

      public int analogTriggerOutput(int triggerHandle, int triggerType) {
        return analog[triggerHandle & 0xffff].get() > 2.5 ? 1 : 0;
      }

      public DoubleValue analogInputVoltage(int channel) {
        return analog[channel];
      }
    };
  }

  private static void later(long ms, Runnable r) {
    Thread th = new Thread(() -> {
      sleep(ms);
      r.run();
    });
    th.setDaemon(true);
    th.start();
  }

  private static void interrupts(Tests t) {
    t.test("digital interrupt waits for the configured edges", () -> {
      BoolValue[] dio = new BoolValue[31];
      for (int i = 0; i < dio.length; i++) {
        dio[i] = new BoolValue("Value");
      }
      InterruptSources.current = provider(dio, new DoubleValue[8]);
      DigitalPorts.Allocation a = DigitalPorts.allocate(3, Handles.DIO);
      a.port.channel = 3;
      int interrupt = InterruptJNI.initializeInterrupts();
      try {
        InterruptJNI.requestInterrupts(interrupt, a.handle, 0);
        InterruptJNI.setInterruptUpSourceEdge(interrupt, true, false);

        long start = System.nanoTime();
        Tests.eq(0L, InterruptJNI.waitForInterrupt(interrupt, 0.15, false), "timeout");
        Tests.check((System.nanoTime() - start) >= 100_000_000L, "waited about the timeout");

        later(100, () -> dio[3].set(true));
        long result = InterruptJNI.waitForInterrupt(interrupt, 5, false);
        Tests.eq(1L << (interrupt & 0xffff), result, "rising edge bit");
        Tests.check(InterruptJNI.readInterruptRisingTimestamp(interrupt) > 0, "rising timestamp");

        later(50, () -> dio[3].set(false));
        Tests.eq(0L, InterruptJNI.waitForInterrupt(interrupt, 0.3, false), "falling is ignored");

        InterruptJNI.setInterruptUpSourceEdge(interrupt, true, true);
        dio[3].set(true);
        later(100, () -> dio[3].set(false));
        Tests.eq(0x100L << (interrupt & 0xffff), InterruptJNI.waitForInterrupt(interrupt, 5, false), "falling edge bit");
        Tests.check(InterruptJNI.readInterruptFallingTimestamp(interrupt) > 0, "falling timestamp");
      } finally {
        InterruptJNI.cleanInterrupts(interrupt);
        DigitalPorts.free(a.handle, Handles.DIO);
        InterruptSources.current = null;
      }
    });

    t.test("releaseWaitingInterrupt wakes a waiter", () -> {
      BoolValue[] dio = {new BoolValue("Value")};
      InterruptSources.current = provider(dio, new DoubleValue[8]);
      DigitalPorts.Allocation a = DigitalPorts.allocate(0, Handles.DIO);
      a.port.channel = 0;
      int interrupt = InterruptJNI.initializeInterrupts();
      try {
        InterruptJNI.requestInterrupts(interrupt, a.handle, 0);
        InterruptJNI.setInterruptUpSourceEdge(interrupt, true, true);
        later(100, () -> InterruptJNI.releaseWaitingInterrupt(interrupt));
        long start = System.nanoTime();
        InterruptJNI.waitForInterrupt(interrupt, 10, false);
        Tests.check((System.nanoTime() - start) < 5_000_000_000L, "released early");
      } finally {
        InterruptJNI.cleanInterrupts(interrupt);
        DigitalPorts.free(a.handle, Handles.DIO);
        InterruptSources.current = null;
      }
    });

    t.test("analog trigger interrupt fires on the trigger state change", () -> {
      DoubleValue[] analog = new DoubleValue[8];
      for (int i = 0; i < analog.length; i++) {
        analog[i] = new DoubleValue("Voltage");
      }
      InterruptSources.current = provider(new BoolValue[31], analog);
      int trigger = Handles.make(2, Handles.ANALOG_TRIGGER);
      int interrupt = InterruptJNI.initializeInterrupts();
      try {
        InterruptJNI.requestInterrupts(interrupt, trigger, 2);
        InterruptJNI.setInterruptUpSourceEdge(interrupt, true, false);
        later(100, () -> analog[2].set(4.0));
        Tests.eq(1L << (interrupt & 0xffff), InterruptJNI.waitForInterrupt(interrupt, 5, false), "rising");
      } finally {
        InterruptJNI.cleanInterrupts(interrupt);
        InterruptSources.current = null;
      }
    });

    t.test("interrupt handles: allocation limit and invalid handles", () -> {
      int[] handles = new int[8];
      try {
        for (int i = 0; i < handles.length; i++) {
          handles[i] = InterruptJNI.initializeInterrupts();
        }
        expect(AllocationException.class, InterruptJNI::initializeInterrupts);
      } finally {
        for (int h : handles) {
          InterruptJNI.cleanInterrupts(h);
        }
      }
      int bad = Handles.make(5, Handles.INTERRUPT);
      expect(HalHandleException.class, () -> InterruptJNI.waitForInterrupt(bad, 0, false));
      expect(HalHandleException.class, () -> InterruptJNI.readInterruptRisingTimestamp(bad));
      expect(HalHandleException.class, () -> InterruptJNI.setInterruptUpSourceEdge(bad, true, true));
      expect(HalHandleException.class, () -> InterruptJNI.releaseWaitingInterrupt(bad));
      int interrupt = InterruptJNI.initializeInterrupts();
      try {
        expect(HalHandleException.class, () -> InterruptJNI.requestInterrupts(interrupt, 0, 0));
        Tests.eq(0L, InterruptJNI.waitForInterrupt(interrupt, 0.01, false), "unconfigured wait times out");
      } finally {
        InterruptJNI.cleanInterrupts(interrupt);
      }
    });
  }

  private static void misc(Tests t) {
    t.test("dma is a stub whose reads report an error", () -> {
      Tests.eq(0, DMAJNI.initialize(), "no handle");
      DMAJNI.free(0);
      DMAJNI.setPause(0, true);
      DMAJNI.startDMA(0, 10);
      DMAJNI.stopDMA(0);
      Tests.eq(0, DMAJNI.setExternalTrigger(0, 0, 0, true, true), "trigger index");
      int[] data = new int[100];
      int[] store = new int[100];
      Arrays.fill(store, 7);
      Tests.eq(0L, DMAJNI.readDMA(0, 0.1, data, store), "timestamp");
      Tests.eq(3, store[25], "read status is error");
      Tests.eq(0, store[22], "capture size");
      Tests.eq(0, store[0], "offsets are zeroed");
      DMAJNISample sample = new DMAJNISample();
      Tests.eq(3, sample.update(0, 0.1), "sample update status");
    });

    t.test("dma sensor read data by handle type", () -> {
      Tests.check(DMAJNI.getSensorReadData(Handles.make(5, Handles.DIO)) != null, "digital source");
      Tests.check(DMAJNI.getSensorReadData(Handles.make(2, Handles.ANALOG_INPUT)) != null, "analog input");
      Tests.check(DMAJNI.getSensorReadData(Handles.make(2, Handles.PWM)) == null, "unsupported handle");
      DMAJNIImpl.encoderBase = h -> h == 1234 ? new int[] {Handles.make(5, Handles.FPGA_ENCODER), 0} : null;
      try {
        Object store = DMAJNI.getSensorReadData(1234);
        Tests.check(store != null, "encoder");
        Tests.eq(17, field(store, "m_valueType"), "high encoders");
        Tests.eq(1, field(store, "m_index"), "index within the high bank");
        Object analog = DMAJNI.getSensorReadData(Handles.make(6, Handles.ANALOG_INPUT));
        Tests.eq(1, field(analog, "m_valueType"), "high analog bank");
        Tests.eq(2, field(analog, "m_index"), "analog index");
      } finally {
        DMAJNIImpl.encoderBase = h -> null;
      }
    });

    t.test("glitch filter select uses the dio port and wraps at 4", () -> {
      DigitalPorts.Allocation a = DigitalPorts.allocate(9, Handles.DIO);
      try {
        DigitalGlitchFilterJNI.setFilterSelect(a.handle, 6);
        Tests.eq(2, DigitalGlitchFilterJNI.getFilterSelect(a.handle), "wrapped index");
        Tests.eq(2, a.port.filterIndex, "stored on the port");
        DigitalGlitchFilterJNI.setFilterPeriod(2, 500);
        Tests.eq(0, DigitalGlitchFilterJNI.getFilterPeriod(2), "period is not simulated");
      } finally {
        DigitalPorts.free(a.handle, Handles.DIO);
      }
      expect(HalHandleException.class, () -> DigitalGlitchFilterJNI.setFilterSelect(a.handle, 1));
      expect(HalHandleException.class, () -> DigitalGlitchFilterJNI.getFilterSelect(0));
    });

    t.test("radio led state round trips and notifies", () -> {
      AtomicInteger seen = new AtomicInteger(-1);
      NotifyCallback cb = (name, value) -> seen.set((int) value.getLong());
      int uid = dev.project516.frcdevwasm.sim.RoboRioData.get().radioLedState.register(cb, false);
      LEDJNI.setRadioLEDState(LEDJNI.RADIO_LED_STATE_ORANGE);
      Tests.eq(LEDJNI.RADIO_LED_STATE_ORANGE, LEDJNI.getRadioLEDState(), "state");
      Tests.eq(LEDJNI.RADIO_LED_STATE_ORANGE, seen.get(), "callback");
      dev.project516.frcdevwasm.sim.RoboRioData.get().radioLedState.cancel(uid);
      LEDJNI.setRadioLEDState(LEDJNI.RADIO_LED_STATE_OFF);
    });
  }

  private static int field(Object o, String name) throws Exception {
    java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.getInt(o);
  }
}
