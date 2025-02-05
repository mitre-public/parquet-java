/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.parquet.crypto.keytools;

import java.io.IOException;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.crypto.AesGcmDecryptor;
import org.apache.parquet.crypto.AesGcmEncryptor;
import org.apache.parquet.crypto.AesMode;
import org.apache.parquet.crypto.KeyAccessDeniedException;
import org.apache.parquet.crypto.ModuleCipherFactory;
import org.apache.parquet.crypto.ParquetCryptoRuntimeException;
import org.apache.parquet.hadoop.BadConfigurationException;
import org.apache.parquet.hadoop.util.ConfigurationUtil;
import org.apache.parquet.hadoop.util.HiddenFileFilter;

public class KeyToolkit {

  /**
   *  Class implementing the KmsClient interface.
   *  KMS stands for “key management service”.
   */
  public static final String KMS_CLIENT_CLASS_PROPERTY_NAME = "parquet.encryption.kms.client.class";
  /**
   * ID of the KMS instance that will be used for encryption (if multiple KMS instances are available).
   */
  public static final String KMS_INSTANCE_ID_PROPERTY_NAME = "parquet.encryption.kms.instance.id";
  /**
   * URL of the KMS instance.
   */
  public static final String KMS_INSTANCE_URL_PROPERTY_NAME = "parquet.encryption.kms.instance.url";
  /**
   * Authorization token that will be passed to KMS.
   */
  public static final String KEY_ACCESS_TOKEN_PROPERTY_NAME = "parquet.encryption.key.access.token";
  /**
   * Use double wrapping - where data encryption keys (DEKs) are encrypted with key encryption keys (KEKs),
   * which in turn are encrypted with master keys.
   * By default, true. If set to false, DEKs are directly encrypted with master keys, KEKs are not used.
   */
  public static final String DOUBLE_WRAPPING_PROPERTY_NAME = "parquet.encryption.double.wrapping";
  /**
   * Lifetime of cached entities (key encryption keys, local wrapping keys, KMS client objects).
   */
  public static final String CACHE_LIFETIME_PROPERTY_NAME = "parquet.encryption.cache.lifetime.seconds";
  /**
   * Store key material inside Parquet file footers; this mode doesn’t produce additional files.
   * By default, true. If set to false, key material is stored in separate files in the same folder,
   * which enables key rotation for immutable Parquet files.
   */
  public static final String KEY_MATERIAL_INTERNAL_PROPERTY_NAME = "parquet.encryption.key.material.store.internally";
  /**
   * Length of data encryption keys (DEKs), randomly generated by parquet key management tools.
   * Can be 128, 192 or 256 bits.
   */
  public static final String DATA_KEY_LENGTH_PROPERTY_NAME = "parquet.encryption.data.key.length.bits";
  /**
   * Length of key encryption keys (KEKs), randomly generated by parquet key management tools.
   * Can be 128, 192 or 256 bits.
   */
  public static final String KEK_LENGTH_PROPERTY_NAME = "parquet.encryption.kek.length.bits";

  public static final boolean DOUBLE_WRAPPING_DEFAULT = true;
  public static final long CACHE_LIFETIME_DEFAULT_SECONDS = 10 * 60; // 10 minutes
  public static final boolean KEY_MATERIAL_INTERNAL_DEFAULT = true;
  public static final int DATA_KEY_LENGTH_DEFAULT = 128;
  public static final int KEK_LENGTH_DEFAULT = 128;

  private static long lastCacheCleanForKeyRotationTime = 0;
  private static Object lastCacheCleanForKeyRotationTimeLock = new Object();
  // KMS servers typically allow to run key rotation once in a few hours / a day.
  // We clean KEK writer cache (if needed) after 1 hour
  private static final int CACHE_CLEAN_PERIOD_FOR_KEY_ROTATION = 60 * 60 * 1000; // 1 hour

  // KMS client two level cache: token -> KMSInstanceId -> KmsClient
  static final TwoLevelCacheWithExpiration<KmsClient> KMS_CLIENT_CACHE_PER_TOKEN = KmsClientCache.INSTANCE.getCache();

  // KEK two level cache for wrapping: token -> MEK_ID -> KeyEncryptionKey
  static final TwoLevelCacheWithExpiration<KeyEncryptionKey> KEK_WRITE_CACHE_PER_TOKEN =
      KEKWriteCache.INSTANCE.getCache();

  // KEK two level cache for unwrapping: token -> KEK_ID -> KEK bytes
  static final TwoLevelCacheWithExpiration<byte[]> KEK_READ_CACHE_PER_TOKEN = KEKReadCache.INSTANCE.getCache();

  private enum KmsClientCache {
    INSTANCE;
    private final TwoLevelCacheWithExpiration<KmsClient> cache = new TwoLevelCacheWithExpiration<>();

    private TwoLevelCacheWithExpiration<KmsClient> getCache() {
      return cache;
    }
  }

  private enum KEKWriteCache {
    INSTANCE;
    private final TwoLevelCacheWithExpiration<KeyEncryptionKey> cache = new TwoLevelCacheWithExpiration<>();

    private TwoLevelCacheWithExpiration<KeyEncryptionKey> getCache() {
      return cache;
    }
  }

  private enum KEKReadCache {
    INSTANCE;
    private final TwoLevelCacheWithExpiration<byte[]> cache = new TwoLevelCacheWithExpiration<>();

    private TwoLevelCacheWithExpiration<byte[]> getCache() {
      return cache;
    }
  }

  static class KeyWithMasterID {
    private final byte[] keyBytes;
    private final String masterID;

    KeyWithMasterID(byte[] keyBytes, String masterID) {
      this.keyBytes = keyBytes;
      this.masterID = masterID;
    }

    byte[] getDataKey() {
      return keyBytes;
    }

    String getMasterID() {
      return masterID;
    }
  }

  static class KeyEncryptionKey {
    private final byte[] kekBytes;
    private final byte[] kekID;
    private String encodedKekID;
    private final String encodedWrappedKEK;

    KeyEncryptionKey(byte[] kekBytes, byte[] kekID, String encodedWrappedKEK) {
      this.kekBytes = kekBytes;
      this.kekID = kekID;
      this.encodedWrappedKEK = encodedWrappedKEK;
    }

    byte[] getBytes() {
      return kekBytes;
    }

    byte[] getID() {
      return kekID;
    }

    String getEncodedID() {
      if (null == encodedKekID) {
        encodedKekID = Base64.getEncoder().encodeToString(kekID);
      }
      return encodedKekID;
    }

    String getEncodedWrappedKEK() {
      return encodedWrappedKEK;
    }
  }

  /**
   * Key rotation. In the single wrapping mode, decrypts data keys with old master keys, then encrypts
   * them with new master keys. In the double wrapping mode, decrypts KEKs (key encryption keys) with old
   * master keys, generates new KEKs and encrypts them with new master keys.
   * Works only if key material is not stored internally in file footers.
   * Not supported in local key wrapping mode.
   * Method can be run by multiple threads, but each thread must work on a different folder.
   * @param folderPath parent path of Parquet files, whose keys will be rotated
   * @param hadoopConfig Hadoop configuration
   * @throws IOException I/O problems
   * @throws ParquetCryptoRuntimeException General parquet encryption problems
   * @throws KeyAccessDeniedException No access to master keys
   * @throws UnsupportedOperationException Master key rotation not supported in the specific configuration
   */
  public static void rotateMasterKeys(String folderPath, Configuration hadoopConfig)
      throws IOException, ParquetCryptoRuntimeException, KeyAccessDeniedException, UnsupportedOperationException {

    if (hadoopConfig.getBoolean(KEY_MATERIAL_INTERNAL_PROPERTY_NAME, false)) {
      throw new UnsupportedOperationException("Key rotation is not supported for internal key material");
    }

    // If process wrote files with double-wrapped keys, clean KEK cache (since master keys are changing).
    // Only once for each key rotation cycle; not for every folder
    long currentTime = System.currentTimeMillis();
    synchronized (lastCacheCleanForKeyRotationTimeLock) {
      if (currentTime - lastCacheCleanForKeyRotationTime > CACHE_CLEAN_PERIOD_FOR_KEY_ROTATION) {
        KEK_WRITE_CACHE_PER_TOKEN.clear();
        lastCacheCleanForKeyRotationTime = currentTime;
      }
    }

    Path parentPath = new Path(folderPath);

    FileSystem hadoopFileSystem = parentPath.getFileSystem(hadoopConfig);
    if (!hadoopFileSystem.exists(parentPath) || !hadoopFileSystem.isDirectory(parentPath)) {
      throw new ParquetCryptoRuntimeException(
          "Couldn't rotate keys - folder doesn't exist or is not a directory: " + folderPath);
    }

    FileStatus[] parquetFilesInFolder = hadoopFileSystem.listStatus(parentPath, HiddenFileFilter.INSTANCE);
    if (parquetFilesInFolder.length == 0) {
      throw new ParquetCryptoRuntimeException("Couldn't rotate keys - no parquet files in folder " + folderPath);
    }

    for (FileStatus fs : parquetFilesInFolder) {
      Path parquetFile = fs.getPath();

      FileKeyMaterialStore keyMaterialStore = new HadoopFSKeyMaterialStore(hadoopFileSystem);
      keyMaterialStore.initialize(parquetFile, hadoopConfig, false);

      FileKeyMaterialStore tempKeyMaterialStore = new HadoopFSKeyMaterialStore(hadoopFileSystem);
      tempKeyMaterialStore.initialize(parquetFile, hadoopConfig, true);

      Set<String> fileKeyIdSet = keyMaterialStore.getKeyIDSet();

      // Start with footer key (to get KMS ID, URL, if needed)
      FileKeyUnwrapper fileKeyUnwrapper = new FileKeyUnwrapper(hadoopConfig, parquetFile, keyMaterialStore);
      String keyMaterialString = keyMaterialStore.getKeyMaterial(KeyMaterial.FOOTER_KEY_ID_IN_FILE);
      KeyWithMasterID key = fileKeyUnwrapper.getDEKandMasterID(KeyMaterial.parse(keyMaterialString));
      KmsClientAndDetails kmsClientAndDetails = fileKeyUnwrapper.getKmsClientAndDetails();

      FileKeyWrapper fileKeyWrapper = new FileKeyWrapper(hadoopConfig, tempKeyMaterialStore, kmsClientAndDetails);
      fileKeyWrapper.getEncryptionKeyMetadata(
          key.getDataKey(), key.getMasterID(), true, KeyMaterial.FOOTER_KEY_ID_IN_FILE);

      fileKeyIdSet.remove(KeyMaterial.FOOTER_KEY_ID_IN_FILE);
      // Rotate column keys
      for (String keyIdInFile : fileKeyIdSet) {
        keyMaterialString = keyMaterialStore.getKeyMaterial(keyIdInFile);
        key = fileKeyUnwrapper.getDEKandMasterID(KeyMaterial.parse(keyMaterialString));
        fileKeyWrapper.getEncryptionKeyMetadata(key.getDataKey(), key.getMasterID(), false, keyIdInFile);
      }

      tempKeyMaterialStore.saveMaterial();

      keyMaterialStore.removeMaterial();

      tempKeyMaterialStore.moveMaterialTo(keyMaterialStore);
    }
  }

  /**
   * Flush any caches that are tied to the (compromised) accessToken
   * @param accessToken access token
   */
  public static void removeCacheEntriesForToken(String accessToken) {
    KMS_CLIENT_CACHE_PER_TOKEN.removeCacheEntriesForToken(accessToken);
    KEK_WRITE_CACHE_PER_TOKEN.removeCacheEntriesForToken(accessToken);
    KEK_READ_CACHE_PER_TOKEN.removeCacheEntriesForToken(accessToken);
  }

  public static void removeCacheEntriesForAllTokens() {
    KMS_CLIENT_CACHE_PER_TOKEN.clear();
    KEK_WRITE_CACHE_PER_TOKEN.clear();
    KEK_READ_CACHE_PER_TOKEN.clear();
  }

  /**
   * Encrypts "key" with "masterKey", using AES-GCM and the "AAD"
   * @param keyBytes the key to encrypt
   * @param masterKeyBytes encryption key
   * @param AAD additional authenticated data
   * @return base64 encoded encrypted key
   */
  public static String encryptKeyLocally(byte[] keyBytes, byte[] masterKeyBytes, byte[] AAD) {
    AesGcmEncryptor keyEncryptor;

    keyEncryptor = (AesGcmEncryptor) ModuleCipherFactory.getEncryptor(AesMode.GCM, masterKeyBytes);

    byte[] encryptedKey = keyEncryptor.encrypt(false, keyBytes, AAD);

    return Base64.getEncoder().encodeToString(encryptedKey);
  }

  /**
   * Decrypts encrypted key with "masterKey", using AES-GCM and the "AAD"
   * @param encodedEncryptedKey base64 encoded encrypted key
   * @param masterKeyBytes encryption key
   * @param AAD additional authenticated data
   * @return decrypted key
   */
  public static byte[] decryptKeyLocally(String encodedEncryptedKey, byte[] masterKeyBytes, byte[] AAD) {
    byte[] encryptedKey = Base64.getDecoder().decode(encodedEncryptedKey);

    AesGcmDecryptor keyDecryptor;

    keyDecryptor = (AesGcmDecryptor) ModuleCipherFactory.getDecryptor(AesMode.GCM, masterKeyBytes);

    return keyDecryptor.decrypt(encryptedKey, 0, encryptedKey.length, AAD);
  }

  static KmsClient getKmsClient(
      String kmsInstanceID,
      String kmsInstanceURL,
      Configuration configuration,
      String accessToken,
      long cacheEntryLifetime) {

    ConcurrentMap<String, KmsClient> kmsClientPerKmsInstanceCache =
        KMS_CLIENT_CACHE_PER_TOKEN.getOrCreateInternalCache(accessToken, cacheEntryLifetime);

    KmsClient kmsClient = kmsClientPerKmsInstanceCache.computeIfAbsent(
        kmsInstanceID,
        (k) -> createAndInitKmsClient(configuration, kmsInstanceID, kmsInstanceURL, accessToken));

    return kmsClient;
  }

  private static KmsClient createAndInitKmsClient(
      Configuration configuration, String kmsInstanceID, String kmsInstanceURL, String accessToken) {

    Class<?> kmsClientClass = null;
    KmsClient kmsClient = null;

    try {
      kmsClientClass = ConfigurationUtil.getClassFromConfig(
          configuration, KMS_CLIENT_CLASS_PROPERTY_NAME, KmsClient.class);

      if (null == kmsClientClass) {
        throw new ParquetCryptoRuntimeException("Unspecified " + KMS_CLIENT_CLASS_PROPERTY_NAME);
      }
      kmsClient = (KmsClient) kmsClientClass.newInstance();
    } catch (InstantiationException | IllegalAccessException | BadConfigurationException e) {
      throw new ParquetCryptoRuntimeException("Could not instantiate KmsClient class: " + kmsClientClass, e);
    }

    kmsClient.initialize(configuration, kmsInstanceID, kmsInstanceURL, accessToken);

    return kmsClient;
  }

  static String formatTokenForLog(String accessToken) {
    int maxTokenDisplayLength = 5;
    if (accessToken.length() <= maxTokenDisplayLength) {
      return accessToken;
    }
    return accessToken.substring(accessToken.length() - maxTokenDisplayLength);
  }

  static boolean stringIsEmpty(String str) {
    return (null == str) || str.isEmpty();
  }

  static class KmsClientAndDetails {
    private KmsClient kmsClient;
    private String kmsInstanceID;
    private String kmsInstanceURL;

    public KmsClientAndDetails(KmsClient kmsClient, String kmsInstanceID, String kmsInstanceURL) {
      this.kmsClient = kmsClient;
      this.kmsInstanceID = kmsInstanceID;
      this.kmsInstanceURL = kmsInstanceURL;
    }

    public KmsClient getKmsClient() {
      return kmsClient;
    }

    public String getKmsInstanceID() {
      return kmsInstanceID;
    }

    public String getKmsInstanceURL() {
      return kmsInstanceURL;
    }
  }
}
