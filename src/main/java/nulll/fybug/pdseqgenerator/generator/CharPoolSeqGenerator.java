package nulll.fybug.pdseqgenerator.generator;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import nulll.fybug.pdseqgenerator.SeqGenerator;

/**
 * <h2>字符池序列生成器.</h2>
 * 生成的序列 {@link Seq} 为时间戳加字符池生成序列号，可自行决定如何拼接成字符串
 * <br/><br/>
 * 序列号由传入的字符池和指定的序列号长度决定，计数器中的每一个计数都代表着对应位置的字符使用字符池中的哪一个，每次调用 {@link #GenerateSeq()} 获取时都会让计数增加
 * <br/><br/>
 * 例如传入 {@code "a1234s"} 作为字符池，长度为7，则第一次调用{@link #GenerateSeq()}获取的序列号为{@code "aaaaaa1"}，第二次调用获得的序列号为{@code "aaaaaa2"}
 *
 * @author fybug
 * @version 0.0.1
 * @see #GenerateSeq()
 * @since generator 0.0.1
 */
public
class CharPoolSeqGenerator implements SeqGenerator {
  private final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
  /** 生成的序列号的长度 */
  private int code_lenght;
  /** 序列号的字符池 */
  @NotNull private char[] char_pool;
  /**
   * 序列号计数器
   * <p>
   * 每一位都代表着对应位置的字符使用 {@link #char_pool} 中的第几个填充
   */
  @NotNull private int[] numbers;
  /** 当前时间戳 */
  private long timestamp;

  public
  CharPoolSeqGenerator(int code_lenght, @NotNull char[] char_pool) {
    this.code_lenght = code_lenght;
    this.char_pool = char_pool;
    updateParameter();
  }

  /**
   * 更新参数
   * <p>
   * 重置时间戳和计数器
   */
  private
  void updateParameter() {
    numbers = new int[code_lenght];
    // 重置时间戳
    timestamp = System.currentTimeMillis() + 1;
  }

  /**
   * 生成序列
   * <p>
   * 在同一个时间戳内序列号为递增，时间戳更新后序列号将会重置<br/>
   * 每位计数的值都代表该位序列号使用字符池中的第几个字符<br/>
   * 计数增加时会增加计数器最后一位，如果计数器的当前位的计数 {@code >= } 字符池的长度 {@code || < 0} 时会被重置为0，并改为增加下一位计数，以此类推，直到有一位增加计数后依旧符合规则时便完成了本次的计数增加
   * <br/><br/>
   * 检查步骤中如果遇到时间戳回滚则会直接重试，直到时间正常<br/>
   * 如遇到计数器溢出则会强制偏移到下一时间戳，并重置序列号然后重试
   *
   * @return 生成的序列实体
   *
   * @see Seq
   */
  @NotNull
  public
  CharPoolSeqGenerator.Seq GenerateSeq() {
    long now;
    StringBuilder stringBuilder = new StringBuilder(code_lenght);
    while( true ){
      LOCK.readLock().lock();
      try {
        // 当前时间
        now = System.currentTimeMillis();

        // 时间回滚，重试
        if ( timestamp > now )
          continue;
        // 时间正常
        if ( timestamp == now ) {
          // 递增计数
          for ( int len = char_pool.length, i = code_lenght - 1; i >= 0; i-- ){
            // 计数位异常
            if ( numbers[i] < 0 || ++numbers[i] >= len ) {
              // 计数溢出，强制偏移到下一时间戳，并重试
              if ( i == 0 ) {
                numbers = new int[code_lenght];
                timestamp = now + 1;
                continue;
              }
              // 重置异常位为0
              numbers[i] = 0;
            } else {
              // 计数正常
              break;
            }
          }
        } else {
          // 重置计数
          numbers = new int[code_lenght];
          timestamp = now;
        }

        // 根据指定的字符池实体化字符串
        for ( int number : numbers )
          stringBuilder.append(char_pool[number]);
        // 生成序列
        return new Seq(now, stringBuilder.toString());
      } finally {
        LOCK.readLock().unlock();
      }
    }
  }

  /**
   * {@inheritDoc}
   * <br/><br/>
   * 输出格式为 时间戳 + 序列号
   *
   * @see #GenerateSeq()
   */
  @NotNull
  @Override
  public
  String nextSeq() { return GenerateSeq().toString(); }

  /**
   * <h2>序列实体.</h2>
   * 序列由时间戳与序列号组成
   *
   * @author fybug
   * @version 0.0.1
   * @since CharPoolSeqGenerator 0.0.1
   */
  @AllArgsConstructor
  public static
  class Seq {
    /** 时间戳 */
    @NotNull public long timestamp;
    /** 序列号 */
    @NotNull public String code;

    @Override
    public
    String toString() { return timestamp + code; }

    @Override
    public final
    boolean equals(Object o) {
      if ( this == o )
        return true;
      if ( !(o instanceof Seq cord) )
        return false;

      return Objects.equals(timestamp, cord.timestamp) && Objects.equals(code, cord.code);
    }

    @Override
    public
    int hashCode() {
      int result = Long.hashCode(timestamp);
      result = 31 * result + code.hashCode();
      return result;
    }
  }

  /**
   * <h2>参数更新器.</h2>
   * 该更新器可以让开发者随时更新并重置参数<br/>
   * 使用 {@code set**} 方法设置参数，但不会更新到生成器中，通过 {@link #updateParameter()} 将参数全部更新到生成器中<br/>
   * 参数更新器与生成器之间存在一个锁 {@link #LOCK}，该锁用于保证参数更新的线程安全<br/>
   *
   * @author fybug
   * @version 0.0.1
   * @since CharPoolSeqGenerator 0.0.1
   */
  public static
  class ParameterReconstructor {
    /** 对应更新的对象 */
    @NotNull private final CharPoolSeqGenerator self;

    /** @see CharPoolSeqGenerator#code_lenght */
    @Getter @Setter private int code_lenght;
    /** @see CharPoolSeqGenerator#char_pool */
    @Getter @Setter private char[] char_pool;

    private
    ParameterReconstructor(@NotNull CharPoolSeqGenerator self) {
      this.self = self;
      // 初始化字段值
      this.code_lenght = self.code_lenght;
      this.char_pool = self.char_pool;
    }

    /**
     * 更新参数
     * <p>
     * 更新时需要等待参数锁 {@link #LOCK}<br/>
     * 更新参数后会调用 {@link CharPoolSeqGenerator#updateParameter()} 重新生成各参数的附加参数，并刷重置时间戳和序列号计数
     *
     * @see CharPoolSeqGenerator#updateParameter()
     */
    public
    void updateParameter() {
      if ( code_lenght < 0 )
        throw new IllegalArgumentException("序列号长度不可<0");
      if ( char_pool == null || char_pool.length == 0 )
        throw new IllegalArgumentException("字符池不可为空");

      // 参数锁
      self.LOCK.writeLock().lock();
      try {
        self.code_lenght = code_lenght;
        self.char_pool = char_pool;
        self.updateParameter();
      } finally {
        self.LOCK.writeLock().unlock();
      }
    }

    /** 获取对应的 {@link CharPoolSeqGenerator} 实例 */
    @NotNull
    public
    CharPoolSeqGenerator getInstance() { return self; }
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
  ParameterReconstructor build() {
    return new CharPoolSeqGenerator(5,
                                    new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'}).parameter_reconstructor();
  }
}
