package nulll.fybug.pdseqgenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;

/**
 * <h2>序列构造器.</h2>
 * 支持组合多个生成器生成的序列，支持动态插桩
 *
 * @author fybug
 * @version 0.0.1
 * @since pdseqgenerator 0.0.1
 */
public abstract
class SeqBuild implements SeqGenerator {
  private final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
  /** 生成器列表 */
  private final ArrayList<SeqGenerator> SeqGeneratorList = new ArrayList<>();
  /** 多个序列间的分隔符 */
  @Getter private String partition = "";

  /**
   * 添加生成器
   * <p>
   * 添加 {@link DynamicGenerator} 则为动态插桩，在生成id时，会替换为传递的生成器数组中对应的生成器
   *
   * @see #nextId(SeqGenerator...)
   */
  @NotNull
  public
  SeqBuild add(@NotNull SeqGenerator... seqGenerator) {
    LOCK.writeLock().lock();
    try {
      SeqGeneratorList.addAll(List.of(seqGenerator));
      SeqGeneratorList.trimToSize();
      return this;
    } finally {
      LOCK.writeLock().unlock();
    }
  }

  /** 重新设置生成器列表 */
  @NotNull
  public
  SeqBuild setSeqGeneratorList(@NotNull List<@NotNull SeqGenerator> SeqGeneratorList) {
    LOCK.writeLock().lock();
    try {
      this.SeqGeneratorList.clear();
      this.SeqGeneratorList.addAll(SeqGeneratorList);
      this.SeqGeneratorList.trimToSize();
      return this;
    } finally {
      LOCK.writeLock().unlock();
    }
  }

  /** 获取生成器列表 */
  @NotNull
  public
  ArrayList<@NotNull SeqGenerator> getSeqGeneratorList() { return new ArrayList<>(SeqGeneratorList); }

  /** 设置序列间的分隔符 */
  @NotNull
  public
  SeqBuild setPartition(@NotNull String partition) {
    LOCK.writeLock().lock();
    try {
      this.partition = partition;
      return this;
    } finally {
      LOCK.writeLock().unlock();
    }
  }

  /**
   * {@inheritDoc}
   *
   * @see #nextId(SeqGenerator...)
   */
  @NotNull
  @Override
  public
  String nextSeq() { return nextId(new SeqGenerator[0]); }

  /**
   * 生成下一个序列
   * <p>
   * 生成格式为 {@code [生成器输出的序列 + 分隔符].....} 如果生成器列表中有动态插桩，则会由传递的生成器数组逐个覆盖对应的动态插桩
   * <br/><br/>
   * 添加生成器，其中有一个动态插桩<br/>
   * {@code add(new DynamicGenerator(), new SnowflakeSeqGenerator().build())}<br/>
   * 生成时传入另外的一个生成器<br/>
   * {@code nextId(new SnowflakeSeqGenerator().build())}<br/>
   * 输出内容为 {@code [生成时传入的第一个生成器的序列 + 分隔符 + 生成前添加的第二个生成器的序列]}
   * <br/><br/>
   * 如果传入的生成器列表不足以覆盖所有的动态插桩，则会重复调用最后一个传入的生成器去填充
   * <p>
   * 添加生成器，其中有一个动态插桩<br/>
   * {@code add(new DynamicGenerator(), new SnowflakeSeqGenerator().build(), new DynamicGenerator())}<br/>
   * 生成时传入另外的一个生成器<br/>
   * {@code nextId(new SnowflakeSeqGenerator().build())}<br/>
   * 输出内容为 {@code [生成时传入的第一个生成器的序列 + 分隔符 + 生成前添加的第二个生成器的序列 + 分隔符 + 生成时传入的第一个生成器的序列]}
   *
   * @param seqGenerators 填充动态插桩的生成器数组
   *
   * @see #nextSeq()
   */
  @NotNull
  public
  String nextId(@NotNull SeqGenerator... seqGenerators) {
    LOCK.readLock().lock();
    try {
      // 没有生成器返回空字符串
      if ( SeqGeneratorList.isEmpty() )
        return "";
      // 当前动态构造器游标
      int i = 0;
      int length = seqGenerators.length - 1;
      // 暂存区
      var stringBuilder = new StringBuilder();

      // 拼接所有生成器生成的序列
      for ( SeqGenerator seqGenerator : SeqGeneratorList ){
        // 动态插桩
        if ( seqGenerator instanceof DynamicGenerator )
          seqGenerator = seqGenerators[i < length ? i++ : i];
        // 拼接序列
        stringBuilder.append(seqGenerator.nextSeq()).append(partition);
      }
      // 去除冗余的分割符
      return stringBuilder.substring(0, stringBuilder.length() - partition.length());
    } finally {
      LOCK.readLock().unlock();
    }
  }

  /**
   * <h2>动态插桩标识.</h2>
   *
   * @author fybug
   * @version 0.0.1
   * @see #nextId(SeqGenerator...)
   * @since IDBuild 0.0.1
   */
  public static final
  class DynamicGenerator implements SeqGenerator {
    @Override
    public
    String nextSeq() { return ""; }
  }
}
