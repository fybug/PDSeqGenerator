package nulll.fybug.pdseqgenerator;
import jakarta.validation.constraints.NotNull;

/**
 * <h2>序列生成器.</h2>
 *
 * @author fybug
 * @version 0.0.1
 * @since pdseqgenerator 0.0.1
 */
public
interface SeqGenerator {
  /**
   * 生成下一个序列
   * <p>
   * 正常使用时应该使用该方法获取的序列
   */
  @NotNull
  String nextSeq();
}
