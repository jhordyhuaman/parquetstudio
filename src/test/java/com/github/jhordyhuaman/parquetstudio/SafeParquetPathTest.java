package com.github.jhordyhuaman.parquetstudio;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jhordyhuaman.parquetstudio.service.SafeParquetPath;
import org.junit.jupiter.api.Test;

class SafeParquetPathTest {

  @Test
  void shortCleanPathIsSafe() {
    assertThat(SafeParquetPath.needsSafeCopy("C:\\data\\file.parquet", true)).isFalse();
    assertThat(SafeParquetPath.needsSafeCopy("/tmp/data/file.parquet", false)).isFalse();
  }

  @Test
  void longPathNeedsCopyOnWindowsOnly() {
    String longPath = "D:\\" + "a".repeat(250) + "\\file.parquet"; // > 240 chars
    assertThat(SafeParquetPath.needsSafeCopy(longPath, true)).isTrue();
    assertThat(SafeParquetPath.needsSafeCopy(longPath, false)).isFalse();
  }

  @Test
  void globCharactersNeedCopyOnAnyOs() {
    assertThat(SafeParquetPath.needsSafeCopy("/data/file [1].parquet", false)).isTrue();
    assertThat(SafeParquetPath.needsSafeCopy("/data/copy*.parquet", false)).isTrue();
    assertThat(SafeParquetPath.needsSafeCopy("/data/wh?t.parquet", false)).isTrue();
    assertThat(SafeParquetPath.needsSafeCopy("/data/{a}.parquet", false)).isTrue();
    assertThat(SafeParquetPath.needsSafeCopy("C:\\data\\file[x].parquet", true)).isTrue();
  }

  @Test
  void hivePartitionEqualsSignIsSafe() {
    assertThat(SafeParquetPath.needsSafeCopy("/data/gf_cutoff_date=2024-02-14/part.parquet", false))
        .isFalse();
  }
}
