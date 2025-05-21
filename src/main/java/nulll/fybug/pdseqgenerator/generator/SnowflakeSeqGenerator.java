package nulll.fybug.pdseqgenerator.generator;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import nulll.fybug.pdseqgenerator.SeqGenerator;

/**
 * <h2>雪花序列生成器.</h2>
 * 默认位长度为 时间戳41位、数据中心id 5位、服务器id 5位、序列号21位，生成的数据格式查看 {@link #nextSeqByLong()}<br/>
 * 序列号在时间戳变化时都会重置，序列号溢出时会进入重试<br/>
 * 可通过 {@link #parameter_reconstructor()} 获取独立的参数更新器以支持动态更新参数，但是每次更新参数时都会重置序列号和时间戳
 *
 * @author fybug
 * @version 0.0.1
 * @since generator 0.0.1
 */
public
class SnowflakeSeqGenerator implements SeqGenerator {
  /**
   * 参数锁
   * <p>
   * 此锁用于保证参数更新的线程安全<br/>
   * 通常只需要调用读锁，只有在外部更新参数时才使用写锁
   *
   * @see ParameterReconstructor
   */
  private final ReentrantReadWriteLock PARAMETER_LOCK = new ReentrantReadWriteLock();
  /**
   * 内容锁
   * <p>
   * 主要用于控制 {@link #timestamp} {@link #sequence} 的并发安全<br/>
   * 通常获取该锁时应该先获取 {@link #PARAMETER_LOCK} 的读锁
   *
   * @see #timestamp
   * @see #sequence
   */
  private ReentrantLock lock2 = new ReentrantLock();

  /** 数据中心id */
  private long dataCenterId = 0;
  /** 服务器id */
  private long workId = 0;

  /**
   * 初始时间戳定位
   * <p>
   * 生成的id的时间戳为 {@code 当前时间戳 - epoch}
   */
  private long epoch = 1719801600000L;
  /** 时间戳的占用位数 */
  private int timestampBits = 41;
  /** 数据中心id的占用位数 */
  private int dataCenterIdBits = 5;
  /** 服务器id的占用位数 */
  private int workerIdBits = 5;
  /** 序列号的占用位数 */
  private int seqBits = 12;

  /** 序列号默认值 */
  private long sequenceDefaultValue = 0;

  /** 时间戳最大值 */
  private long timestampMaxValue;
  /** 序列号最大值 */
  private long seqMaxValue;

  /** 服务器id向左移动位数(seqBits占用位数) */
  private int workIdShift;
  /** 数据中心id向左移动位数(seqBits占用位数 + workId占用位数) */
  private int dataCenterIdShift;
  /** 时间戳向左移动位位数(seqBits占用位数 + workId占用位数 + dataCenterId占用位数) */
  private int timestampShift;

  /** 当前时间戳 */
  private long timestamp;
  /** 当前序列号 */
  private long sequence;

  public
  SnowflakeSeqGenerator() { updateParameter(); }

  /**
   * 更新参数
   * <p>
   * 更新各参数的最大值与位移动的参数，并重置时间戳和序列号
   * <p>
   * 最大值算法为 {@code ~(-1L << 对应参数的位长度)}
   */
  private
  void updateParameter() {
    // 默认相当于 2^41-1 = 2199023255551
    timestampMaxValue = ~(-1L << timestampBits);
    // 数据中心id最大值,默认相当于 2^5-1 = 31
    long dataCenterIdMaxValue = ~(-1L << dataCenterIdBits);
    // 服务器id最大值,默认相当于 2^5-1 = 31
    long workerIdMaxValue = ~(-1L << workerIdBits);
    // 默认相当于 2^12-1 = 4095
    seqMaxValue = ~(-1L << seqBits);

    if ( dataCenterId < 0 || dataCenterId > dataCenterIdMaxValue )
      throw new IllegalArgumentException("数据中心id不合法");
    if ( workId < 0 || workId > workerIdMaxValue )
      throw new IllegalArgumentException("服务器id不合法");

    /* 重置位移动参数 */
    workIdShift = seqBits;
    dataCenterIdShift = workIdShift + workerIdBits;
    timestampShift = dataCenterIdShift + dataCenterIdBits;

    // 重置序列号
    sequence = sequenceDefaultValue;
    // 重置时间戳
    timestamp = System.currentTimeMillis() + 1;
  }

  /**
   * 生成下一个序列
   * <p>
   * 生成的序列为格式 {@code 相对时间戳 + 数据中心id + 服务器id + 序列号} 的 long<br/>
   * 相对时间戳使用 {@code 当前时间戳 - 初始时间戳}，时间戳异常时（如时间回滚）会直接重试，直到时间正常<br/>
   * 序列号溢出时会强制偏移到下一时间戳，并重置序列号然后重试
   *
   * @return 生成的序列
   *
   * @see #nextSeq()
   */
  public
  long nextSeqByLong() {
    // 当前分配的序列号
    long s;
    // 当前时间戳
    long now;

    while( true ){
      // 上参数锁
      PARAMETER_LOCK.readLock().lock();
      try {
        // 当前时间
        now = System.currentTimeMillis();

        // 上内容锁
        lock2.lock();
        try {
          // 时间可能被回滚，重试
          if ( timestamp > now )
            continue;
          // 时间正常
          if ( timestamp == now ) {
            // 序列号自增，并记录
            s = sequence = sequence + 1;
            // 序列号溢出，强制偏移到下一时间戳，并重试
            if ( s > seqMaxValue ) {
              // 强制偏移时间戳记录
              timestamp = now + 1;
              // 重置序列号
              sequence = sequenceDefaultValue;
              continue;
            }
          } else {
            // 重置序列号
            s = sequence = sequenceDefaultValue;
            // 更新时间戳
            timestamp = now;
          }
        } finally {
          lock2.unlock();
        }

        // 校准为相对时间戳
        long tmp = now - epoch;
        // 时间戳溢出
        if ( tmp > timestampMaxValue )
          throw new IllegalArgumentException("时间戳溢出");

        // 合并为序列
        return (tmp << timestampShift) | (dataCenterId << dataCenterIdShift) | (workId << workIdShift) | (s);
      } finally {
        PARAMETER_LOCK.readLock().unlock();
      }
    }
  }

  /**
   * {@inheritDoc}
   *
   * @see #nextSeqByLong()
   */
  @NotNull
  @Override
  public
  String nextSeq() { return Long.toString(nextSeqByLong()); }

  /**
   * <h2>参数更新器.</h2>
   * 该更新器可以让开发者随时更新并重置参数<br/>
   * 使用 {@code set**} 方法设置参数，但不会更新到生成器中，通过 {@link #updateParameter()} 将参数全部更新到生成器中<br/>
   * 参数更新器与生成器之间存在一个锁 {@link #PARAMETER_LOCK}，该锁用于保证参数更新的线程安全<br/>
   *
   * @author fybug
   * @version 0.0.1
   * @since SnowflakeSeqGenerator 0.0.1
   */
  public static
  class ParameterReconstructor {
    /** 对应更新的对象 */
    @NotNull private final SnowflakeSeqGenerator self;

    /** @see SnowflakeSeqGenerator#lock2 */
    @Getter @Setter private ReentrantLock lock;
    /** @see SnowflakeSeqGenerator#epoch */
    @Getter @Setter private long epoch;
    /** @see SnowflakeSeqGenerator#timestampBits */
    @Getter @Setter private int timestampBits;
    /** @see SnowflakeSeqGenerator#dataCenterIdBits */
    @Getter @Setter private int dataCenterIdBits;
    /** @see SnowflakeSeqGenerator#workerIdBits */
    @Getter @Setter private int workerIdBits;
    /** @see SnowflakeSeqGenerator#seqBits */
    @Getter @Setter private int seqBits;
    /** @see SnowflakeSeqGenerator#sequenceDefaultValue */
    @Getter @Setter private long sequenceDefaultValue;
    /** @see SnowflakeSeqGenerator#dataCenterId */
    @Getter @Setter private long dataCenterId;
    /** @see SnowflakeSeqGenerator#workId */
    @Getter @Setter private long workId;

    private
    ParameterReconstructor(@NotNull SnowflakeSeqGenerator self) {
      this.self = self;
      // 初始化字段值
      this.lock = self.lock2;
      this.epoch = self.epoch;
      this.timestampBits = self.timestampBits;
      this.dataCenterIdBits = self.dataCenterIdBits;
      this.workerIdBits = self.workerIdBits;
      this.seqBits = self.seqBits;
      this.sequenceDefaultValue = self.sequenceDefaultValue;
      this.dataCenterId = self.dataCenterId;
      this.workId = self.workId;
    }

    /**
     * 更新参数
     * <p>
     * 更新时需要等待参数锁 {@link #PARAMETER_LOCK}<br/>
     * 更新参数后会调用 {@link SnowflakeSeqGenerator#updateParameter()} 重新生成各参数的附加参数，并刷重置时间戳和序列号
     *
     * @see SnowflakeSeqGenerator#updateParameter()
     */
    public
    void updateParameter() {
      // 位长总和
      int con = timestampBits + dataCenterIdBits + workerIdBits + seqBits;
      // 位长绝对值总和
      int abscon = Math.abs(timestampBits) + Math.abs(dataCenterIdBits) + Math.abs(workerIdBits) + Math.abs(seqBits);

      if ( con != abscon || con > 63 || con < 0 )
        throw new IllegalArgumentException("所有可设置参数不得为负数且位长度总和不得>63并且不得<0");
      if ( epoch < 0 )
        throw new IllegalArgumentException("初始时间戳不可<0");
      if ( lock != null )
        throw new IllegalArgumentException("锁不可为空");
      if ( sequenceDefaultValue < 0 )
        throw new IllegalArgumentException("默认值不可为空");

      // 参数锁
      self.PARAMETER_LOCK.writeLock().lock();
      try {
        self.lock2 = lock;
        self.epoch = epoch;
        self.timestampBits = timestampBits;
        self.dataCenterIdBits = dataCenterIdBits;
        self.workerIdBits = workerIdBits;
        self.seqBits = seqBits;
        self.sequenceDefaultValue = sequenceDefaultValue;
        self.dataCenterId = dataCenterId;
        self.workId = workId;
        self.updateParameter();
      } finally {
        self.PARAMETER_LOCK.writeLock().unlock();
      }
    }

    /** 获取对应的 {@link SnowflakeSeqGenerator} 实例 */
    @NotNull
    public
    SnowflakeSeqGenerator getInstance() { return self; }
  }

  /**
   * 获取参数更新器
   * <p>
   * 使用该更新器可以随时更新并重置序列参数，独立与生成器但内包含生成器本身
   *
   * @return 本实例的参数更新器
   */
  @NotNull
  public
  ParameterReconstructor parameter_reconstructor() { return new ParameterReconstructor(this); }

  /**
   * 获取构造器
   * <p>
   * 返回内容与 {@link #parameter_reconstructor()} 一致，但会先构造一个新的生成器对象
   *
   * @see #parameter_reconstructor()
   */
  @NotNull
  public static
  ParameterReconstructor build() { return new SnowflakeSeqGenerator().parameter_reconstructor(); }
}
