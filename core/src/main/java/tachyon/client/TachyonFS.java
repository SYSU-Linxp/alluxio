/*
 * Licensed to the University of California, Berkeley under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package tachyon.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.Closer;

import tachyon.Constants;
import tachyon.StorageDirId;
import tachyon.TachyonURI;
import tachyon.UnderFileSystem;
import tachyon.client.table.RawTable;
import tachyon.conf.CommonConf;
import tachyon.conf.UserConf;
import tachyon.master.MasterClient;
import tachyon.thrift.ClientBlockInfo;
import tachyon.thrift.ClientDependencyInfo;
import tachyon.thrift.ClientFileInfo;
import tachyon.thrift.ClientRawTableInfo;
import tachyon.thrift.ClientWorkerInfo;
import tachyon.thrift.NetAddress;
import tachyon.thrift.WorkerDirInfo;
import tachyon.util.CommonUtils;
import tachyon.util.ThreadFactoryUtils;
import tachyon.worker.WorkerClient;

/**
 * Tachyon's user client API. It contains a MasterClient and several WorkerClients depending on how
 * many workers the client program is interacting with.
 */
public class TachyonFS extends AbstractTachyonFS {

  /**
   * Create a TachyonFS handler.
   * 
   * @param tachyonPath a Tachyon path contains master address. e.g., tachyon://localhost:19998,
   *        tachyon://localhost:19998/ab/c.txt
   * @return the corresponding TachyonFS hanlder
   * @throws IOException
   * @see #get(tachyon.TachyonURI)
   */
  @Deprecated
  public static synchronized TachyonFS get(String tachyonPath) throws IOException {
    return get(new TachyonURI(tachyonPath));
  }

  /**
   * Create a TachyonFS handler.
   * 
   * @param tachyonURI a Tachyon URI contains master address. e.g., tachyon://localhost:19998,
   *        tachyon://localhost:19998/ab/c.txt
   * @return the corresponding TachyonFS handler
   * @throws IOException
   */
  public static synchronized TachyonFS get(final TachyonURI tachyonURI) throws IOException {
    if (tachyonURI == null) {
      throw new IOException("Tachyon Uri cannot be null. Use " + Constants.HEADER + "host:port/ ,"
          + Constants.HEADER_FT + "host:port/");
    } else {
      String scheme = tachyonURI.getScheme();
      if (scheme == null || tachyonURI.getHost() == null || tachyonURI.getPort() == -1
          || (!Constants.SCHEME.equals(scheme) && !Constants.SCHEME_FT.equals(scheme))) {
        throw new IOException("Invalid Tachyon URI: " + tachyonURI + ". Use " + Constants.HEADER
            + "host:port/ ," + Constants.HEADER_FT + "host:port/");
      }
      return new TachyonFS(tachyonURI);
    }
  }

  /**
   * Create a TachyonFS handler.
   * 
   * @param masterHost master host details
   * @param masterPort port master listens on
   * @param zookeeperMode use zookeeper
   * 
   * @return the corresponding TachyonFS hanlder
   * @throws IOException
   */
  public static synchronized TachyonFS get(String masterHost, int masterPort, boolean zookeeperMode)
      throws IOException {
    return new TachyonFS(new InetSocketAddress(masterHost, masterPort), zookeeperMode);
  }

  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);
  private final long mUserQuotaUnitBytes = UserConf.get().QUOTA_UNIT_BYTES;
  private final ExecutorService mExecutorService;
  private static final int FAILED_SPACE_REQUEST_LIMITS = UserConf.get().FAILED_SPACE_REQUEST_LIMITS;

  // The RPC client talks to the system master.
  private final MasterClient mMasterClient;
  // The Master address.
  private final InetSocketAddress mMasterAddress;
  // The RPC client talks to the local worker if there is one.
  private final WorkerClient mWorkerClient;
  private final Closer mCloser = Closer.create();
  // Whether use ZooKeeper or not
  private final boolean mZookeeperMode;
  // Cached ClientFileInfo
  private final Map<String, ClientFileInfo> mPathToClientFileInfo =
      new HashMap<String, ClientFileInfo>();
  private final Map<Integer, ClientFileInfo> mIdToClientFileInfo =
      new HashMap<Integer, ClientFileInfo>();

  private UnderFileSystem mUnderFileSystem;

  // All Blocks has been locked.
  private final Map<Long, Set<Integer>> mLockedBlockIds = new HashMap<Long, Set<Integer>>();
  // Mapping from block id to id of the StorageDir in which the block is locked
  private final Map<Long, Long> mLockedBlockIdToStorageDirId = new HashMap<Long, Long>();

  // Each user facing block has a unique block lock id.
  private final AtomicInteger mBlockLockId = new AtomicInteger(0);

  // Mapping from Id to Available space of each StorageDir.
  private final Map<Long, Long> mIdToAvailableSpaceBytes = new HashMap<Long, Long>();
  // Mapping from Id to root path of each StorageDir.
  private final Map<Long, String> mIdToWorkerDirPath = new HashMap<Long, String>();
  // Mapping from Id to under file system of each StorageDir
  private final Map<Long, UnderFileSystem> mIdToDirFS = new HashMap<Long, UnderFileSystem>();
  // Mapping from Id to user temporary path of each StorageDir.
  private final Map<Long, String> mIdToUserLocalTempFolder = new HashMap<Long, String>();

  private TachyonFS(TachyonURI tachyonURI) throws IOException {
    this(new InetSocketAddress(tachyonURI.getHost(), tachyonURI.getPort()), tachyonURI.getScheme()
        .equals(Constants.SCHEME_FT));
  }

  private TachyonFS(InetSocketAddress masterAddress, boolean zookeeperMode) throws IOException {
    mMasterAddress = masterAddress;
    mZookeeperMode = zookeeperMode;

    mExecutorService =
        Executors.newFixedThreadPool(2, ThreadFactoryUtils.daemon("client-heartbeat-%d"));

    mMasterClient =
        mCloser.register(new MasterClient(mMasterAddress, mZookeeperMode, mExecutorService));
    mWorkerClient = mCloser.register(new WorkerClient(mMasterClient, mExecutorService));
    initializeDirFS(getWorkerDirInfos());
  }

  /**
   * Update the latest block access time on the worker.
   * 
   * @param blockInfo the information of the block
   * @throws IOException
   */
  synchronized void accessLocalBlock(ClientBlockInfo blockInfo) throws IOException {
    Map<NetAddress, Long> storageDirIds = blockInfo.getStorageDirIds();
    long blockId = blockInfo.getBlockId();
    NetAddress workerNetAddress = mWorkerClient.getNetAddress();
    Long storageDirId = storageDirIds.get(workerNetAddress);
    if (storageDirId != null) {
      accessLocalBlock(storageDirId, blockId);
    }
  }

  /**
   * Update the latest block access time in certain StorageDir on the worker.
   * 
   * @param storageDirId the id of the StorageDir which contains the block
   * @param blockId the local block's id
   * @throws IOException
   */
  synchronized void accessLocalBlock(long storageDirId, long blockId) throws IOException {
    if (mWorkerClient.isLocal()) {
      mWorkerClient.accessBlock(storageDirId, blockId);
    }
  }

  /**
   * Notify the worker that the checkpoint file of the file mFileId has been added.
   * 
   * @param fid the file id
   * @throws IOException
   */
  synchronized void addCheckpoint(int fid) throws IOException {
    mWorkerClient.addCheckpoint(mMasterClient.getUserId(), fid);
  }

  /**
   * Notify the worker to checkpoint the file asynchronously
   * 
   * @param fid the file id
   * @return true if succeed, false otherwise
   * @throws IOException
   */
  synchronized boolean asyncCheckpoint(int fid) throws IOException {
    return mWorkerClient.asyncCheckpoint(fid);
  }

  /**
   * Notify the worker the block is cached.
   * 
   * @param storageDirId the id of the StorageDir which contains the block
   * @param blockId the block id
   * @throws IOException
   */
  public synchronized void cacheBlock(long storageDirId, long blockId) throws IOException {
    mWorkerClient.cacheBlock(storageDirId, blockId);
  }

  /**
   * Close the client. Close the connections to both to master and worker
   * 
   * @throws IOException
   */
  @Override
  public synchronized void close() throws IOException {
    if (mWorkerClient.isConnected()) {
      for (Entry<Long, Long> availableSpace : mIdToAvailableSpaceBytes.entrySet()) {
        if (availableSpace.getValue() > 0) {
          mWorkerClient.returnSpace(mMasterClient.getUserId(), availableSpace.getKey(),
              availableSpace.getValue());
        }
      }
      mWorkerClient.close();
    }
    try {
      mCloser.close();
    } finally {
      mExecutorService.shutdown();
    }
  }

  /**
   * The file is complete.
   * 
   * @param fid the file id
   * @throws IOException
   */
  synchronized void completeFile(int fid) throws IOException {
    mMasterClient.user_completeFile(fid);
  }

  /**
   * Create a user local temporary folder and return it
   * 
   * @param storageDirId the id of the StorageDir which the temporary folder will be created in.
   * @return the local temporary folder for the user or null if unable to allocate one.
   * @throws IOException
   */
  public synchronized String createAndGetUserLocalTempFolder(long storageDirId) throws IOException {
    String userTempFolder = mWorkerClient.getUserTempFolder();

    if (StringUtils.isBlank(userTempFolder)) {
      LOG.error("Unable to get local temporary folder \"{}\" for user.", userTempFolder);
      return null;
    }

    if (mIdToUserLocalTempFolder.containsKey(storageDirId)) {
      return mIdToUserLocalTempFolder.get(storageDirId);
    }

    if (mIdToWorkerDirPath.containsKey(storageDirId)) {
      String dirPath = mIdToWorkerDirPath.get(storageDirId);
      String userLocalTempFolder = CommonUtils.concat(dirPath, userTempFolder);
      UnderFileSystem dirFS = mIdToDirFS.get(storageDirId);
      boolean ret = false;
      if (dirFS.exists(userLocalTempFolder)) {
        if (dirFS.isFile(userLocalTempFolder)) {
          ret = dirFS.mkdirs(userLocalTempFolder, true);
        } else {
          ret = true;
        }
      } else {
        ret = dirFS.mkdirs(userLocalTempFolder, true);
      }
      if (ret) {
        // Only supports local folder
        CommonUtils.changeLocalFileToFullPermission(userLocalTempFolder);
        LOG.info("Folder " + userLocalTempFolder + " was created!");
        mIdToUserLocalTempFolder.put(storageDirId, userLocalTempFolder);
        return userLocalTempFolder;
      } else {
        LOG.error("Failed to create folder " + userLocalTempFolder);
      }
    }

    return null;
  }

  /**
   * Create a user UnderFileSystem temporary folder and return it
   * 
   * @return the UnderFileSystem temporary folder
   * @throws IOException
   */
  synchronized String createAndGetUserUfsTempFolder() throws IOException {
    String tmpFolder = mWorkerClient.getUserUfsTempFolder();
    if (tmpFolder == null) {
      return null;
    }

    if (mUnderFileSystem == null) {
      mUnderFileSystem = UnderFileSystem.get(tmpFolder);
    }

    mUnderFileSystem.mkdirs(tmpFolder, true);

    return tmpFolder;
  }

  /**
   * Create a Dependency
   * 
   * @param parents the dependency's input files
   * @param children the dependency's output files
   * @param commandPrefix
   * @param data
   * @param comment
   * @param framework
   * @param frameworkVersion
   * @param dependencyType the dependency's type, Wide or Narrow
   * @param childrenBlockSizeByte the block size of the dependency's output files
   * @return the dependency's id
   * @throws IOException
   */
  public synchronized int createDependency(List<String> parents, List<String> children,
      String commandPrefix, List<ByteBuffer> data, String comment, String framework,
      String frameworkVersion, int dependencyType, long childrenBlockSizeByte) throws IOException {
    return mMasterClient.user_createDependency(parents, children, commandPrefix, data, comment,
        framework, frameworkVersion, dependencyType, childrenBlockSizeByte);
  }

  /**
   * Creates a new file in the file system.
   * 
   * @param path The path of the file
   * @param ufsPath The path of the file in the under file system. If this is empty, the file does
   *        not exist in the under file system yet.
   * @param blockSizeByte The size of the block in bytes. It is -1 iff ufsPath is non-empty.
   * @param recursive Creates necessary parent folders if true, not otherwise.
   * @return The file id, which is globally unique.
   */
  @Override
  public synchronized int createFile(TachyonURI path, TachyonURI ufsPath, long blockSizeByte,
      boolean recursive) throws IOException {
    validateUri(path);
    return mMasterClient.user_createFile(path.getPath(), ufsPath.toString(), blockSizeByte,
        recursive);
  }

  /**
   * Create a file with the default block size (1GB) in the system. It also creates necessary
   * folders along the path. // TODO It should not create necessary path.
   * 
   * @param path the path of the file
   * @return The unique file id. It returns -1 if the creation failed.
   * @throws IOException If file already exists, or path is invalid.
   */
  @Deprecated
  public synchronized int createFile(String path) throws IOException {
    return createFile(new TachyonURI(path));
  }

  /**
   * Create a RawTable and return its id
   * 
   * @param path the RawTable's path
   * @param columns number of columns it has
   * @return the id if succeed, -1 otherwise
   * @throws IOException
   */
  public synchronized int createRawTable(TachyonURI path, int columns) throws IOException {
    return createRawTable(path, columns, ByteBuffer.allocate(0));
  }

  /**
   * Create a RawTable and return its id
   * 
   * @param path the RawTable's path
   * @param columns number of columns it has
   * @param metadata the meta data of the RawTable
   * @return the id if succeed, -1 otherwise
   * @throws IOException
   */
  public synchronized int createRawTable(TachyonURI path, int columns, ByteBuffer metadata)
      throws IOException {
    validateUri(path);
    if (columns < 1 || columns > CommonConf.get().MAX_COLUMNS) {
      throw new IOException("Column count " + columns + " is smaller than 1 or " + "bigger than "
          + CommonConf.get().MAX_COLUMNS);
    }

    return mMasterClient.user_createRawTable(path.getPath(), columns, metadata);
  }

  /**
   * Deletes a file or folder
   * 
   * @param fileId The id of the file / folder. If it is not -1, path parameter is ignored.
   *        Otherwise, the method uses the path parameter.
   * @param path The path of the file / folder. It could be empty iff id is not -1.
   * @param recursive If fileId or path represents a non-empty folder, delete the folder recursively
   *        or not
   * @return true if deletes successfully, false otherwise.
   * @throws IOException
   */
  @Override
  public synchronized boolean delete(int fileId, TachyonURI path, boolean recursive)
      throws IOException {
    validateUri(path);
    return mMasterClient.user_delete(fileId, path.getPath(), recursive);
  }

  /**
   * Delete the file denoted by the path.
   * 
   * @param path the file path
   * @param recursive if delete the path recursively.
   * @return true if the deletion succeed (including the case that the path does not exist in the
   *         first place), false otherwise.
   * @throws IOException
   */
  @Deprecated
  public synchronized boolean delete(String path, boolean recursive) throws IOException {
    return delete(new TachyonURI(path), recursive);
  }

  /**
   * Return whether the file exists or not
   * 
   * @param path the file's path in Tachyon file system
   * @return true if it exists, false otherwise
   * @throws IOException
   */
  public synchronized boolean exist(TachyonURI path) throws IOException {
    return getFileStatus(-1, path, false) != null;
  }

  /**
   * Get the block id by the file id and block index. it will check whether the file and the block
   * exist.
   * 
   * @param fileId the file id
   * @param blockIndex The index of the block in the file.
   * @return the block id if exists
   * @throws IOException if the file does not exist, or connection issue.
   */
  public synchronized long getBlockId(int fileId, int blockIndex) throws IOException {
    ClientFileInfo info = getFileStatus(fileId, true);

    if (info == null) {
      throw new IOException("File " + fileId + " does not exist.");
    }

    if (info.blockIds.size() > blockIndex) {
      return info.blockIds.get(blockIndex);
    }

    return mMasterClient.user_getBlockId(fileId, blockIndex);
  }

  /**
   * @return a new block lock id
   */
  synchronized int getBlockLockId() {
    return mBlockLockId.getAndIncrement();
  }

  /**
   * Get a ClientBlockInfo by blockId
   * 
   * @param blockId the id of the block
   * @return the ClientBlockInfo of the specified block
   * @throws IOException
   */
  synchronized ClientBlockInfo getClientBlockInfo(long blockId) throws IOException {
    return mMasterClient.user_getClientBlockInfo(blockId);
  }

  /**
   * Get a ClientDependencyInfo by the dependency id
   * 
   * @param depId the dependency id
   * @return the ClientDependencyInfo of the specified dependency
   * @throws IOException
   */
  public synchronized ClientDependencyInfo getClientDependencyInfo(int depId) throws IOException {
    return mMasterClient.getClientDependencyInfo(depId);
  }

  /**
   * Get <code>TachyonFile</code> based on the file id.
   * 
   * NOTE: This *will* use cached file metadata, and so will not see changes to dynamic properties,
   * such as the pinned flag. This is also different from the behavior of getFile(path), which by
   * default will not use cached metadata.
   * 
   * @param fid file id.
   * @return TachyonFile of the file id, or null if the file does not exist.
   */
  public synchronized TachyonFile getFile(int fid) throws IOException {
    return getFile(fid, true);
  }

  /**
   * Get <code>TachyonFile</code> based on the file id. If useCachedMetadata, this will not see
   * changes to the file's pin setting, or other dynamic properties.
   * 
   * @return TachyonFile of the file id, or null if the file does not exist.
   */
  public synchronized TachyonFile getFile(int fid, boolean useCachedMetadata) throws IOException {
    if (!useCachedMetadata || !mIdToClientFileInfo.containsKey(fid)) {
      ClientFileInfo clientFileInfo = getFileStatus(fid, TachyonURI.EMPTY_URI);
      if (clientFileInfo == null) {
        return null;
      }
      mIdToClientFileInfo.put(fid, clientFileInfo);
    }
    return new TachyonFile(this, fid);
  }

  /**
   * Get <code>TachyonFile</code> based on the path. Does not utilize the file metadata cache.
   * 
   * @param path file path.
   * @return TachyonFile of the path, or null if the file does not exist.
   * @throws IOException
   */
  public synchronized TachyonFile getFile(TachyonURI path) throws IOException {
    validateUri(path);
    return getFile(path, false);
  }

  /**
   * Get <code>TachyonFile</code> based on the path. Does not utilize the file metadata cache.
   * 
   * @param path file path.
   * @return TachyonFile of the path, or null if the file does not exist.
   * @throws IOException
   */
  @Deprecated
  public synchronized TachyonFile getFile(String path) throws IOException {
    return getFile(new TachyonURI(path));
  }

  /**
   * Get <code>TachyonFile</code> based on the path. If useCachedMetadata, this will not see changes
   * to the file's pin setting, or other dynamic properties.
   */
  @Deprecated
  public synchronized TachyonFile getFile(String path, boolean useCachedMetadata)
      throws IOException {
    return getFile(new TachyonURI(path), useCachedMetadata);
  }

  /**
   * Get <code>TachyonFile</code> based on the path. If useCachedMetadata, this will not see changes
   * to the file's pin setting, or other dynamic properties.
   */
  public synchronized TachyonFile getFile(TachyonURI path, boolean useCachedMetadata)
      throws IOException {
    validateUri(path);
    ClientFileInfo clientFileInfo = getFileStatus(-1, path, useCachedMetadata);
    if (clientFileInfo == null) {
      return null;
    }
    return new TachyonFile(this, clientFileInfo.getId());
  }

  /**
   * Get all the blocks' info of the file
   * 
   * @param fid the file id
   * @return the list of the blocks' info
   * @throws IOException
   */
  public synchronized List<ClientBlockInfo> getFileBlocks(int fid) throws IOException {
    // TODO Should read from mClientFileInfos if possible. Should add timeout to improve this.
    return mMasterClient.user_getFileBlocks(fid, "");
  }

  /**
   * Get file id by the path. It will check if the path exists.
   * 
   * @param path the path in Tachyon file system
   * @return the file id if exists, -1 otherwise
   * @throws IOException
   */
  public synchronized int getFileId(TachyonURI path) throws IOException {
    try {
      return getFileStatus(-1, path, false).getId();
    } catch (IOException e) {
      return -1;
    }
  }

  @Override
  public ClientFileInfo getFileStatus(int fileId, TachyonURI path) throws IOException {
    return getFileStatus(fileId, path, false);
  }

  /**
   * Get ClientFileInfo object based on fileId.
   * 
   * @param fileId the file id of the file or folder.
   * @param useCachedMetadata if true use the local cached meta data
   * @return the ClientFileInfo of the file. null if the file does not exist.
   * @throws IOException
   */
  public synchronized ClientFileInfo getFileStatus(int fileId, boolean useCachedMetadata)
      throws IOException {
    return getFileStatus(fileId, TachyonURI.EMPTY_URI, useCachedMetadata);
  }

  /**
   * Advanced API.
   * 
   * Gets the ClientFileInfo object that represents the fileId, or the path if fileId is -1.
   * 
   * @param fileId the file id of the file or folder.
   * @param path the path of the file or folder. valid iff fileId is -1.
   * @param useCachedMetadata if true use the local cached meta data
   * @return the ClientFileInfo of the file. null if the file does not exist.
   * @throws IOException
   */
  public synchronized ClientFileInfo getFileStatus(int fileId, TachyonURI path,
      boolean useCachedMetadata) throws IOException {
    ClientFileInfo info = null;
    boolean updated = false;

    validateUri(path);

    if (fileId != -1) {
      info = mIdToClientFileInfo.get(fileId);
      if (!useCachedMetadata || info == null) {
        info = mMasterClient.getFileStatus(fileId, TachyonURI.EMPTY_URI.getPath());
        updated = true;
      }

      if (info.getId() == -1) {
        mIdToClientFileInfo.remove(fileId);
        return null;
      }

      path = new TachyonURI(info.getPath());
    } else {
      info = mPathToClientFileInfo.get(path.getPath());
      if (!useCachedMetadata || info == null) {
        info = mMasterClient.getFileStatus(-1, path.getPath());
        updated = true;
      }

      if (info.getId() == -1) {
        mPathToClientFileInfo.remove(path.getPath());
        return null;
      }

      fileId = info.getId();
    }

    if (updated) {
      // TODO LRU on this Map.
      mIdToClientFileInfo.put(fileId, info);
      mPathToClientFileInfo.put(path.getPath(), info);
    }

    return info;
  }

  /**
   * Get space from available space of StorageDirs
   * 
   * @param requestSpaceBytes size to request in bytes
   * @return the id of the StorageDir allocated
   * @throws IOException
   */
  private synchronized long getFromAvaliableSpace(long requestSpaceBytes) throws IOException {
    long storageDirId = StorageDirId.unknownId();
    for (Entry<Long, Long> entry : mIdToAvailableSpaceBytes.entrySet()) {
      if (entry.getValue() >= requestSpaceBytes) {
        storageDirId = entry.getKey();
        mIdToAvailableSpaceBytes.put(storageDirId, entry.getValue() - requestSpaceBytes);
        break;
      }
    }
    return storageDirId;
  }

  /**
   * Get space from available space of specific StorageDir for the user
   * 
   * @param storageDirId the id of the StorageDir
   * @param requestSpaceBytes size of the space to request in bytes
   * @return true if success, false otherwise
   * @throws IOException
   */
  private synchronized boolean getFromAvaliableSpace(long storageDirId, long requestSpaceBytes)
      throws IOException {
    if (mIdToAvailableSpaceBytes.containsKey(storageDirId)) {
      long availableSpaceBytes = mIdToAvailableSpaceBytes.get(storageDirId);
      if (availableSpaceBytes >= requestSpaceBytes) {
        mIdToAvailableSpaceBytes.put(storageDirId, availableSpaceBytes - requestSpaceBytes);
        return true;
      }
    }
    return false;
  }

  /**
   * Get path of the block file
   * 
   * @param blockInfo information of the block
   * @return the path of the block file
   * @throws IOException
   */
  synchronized String getLocalBlockFilePath(ClientBlockInfo blockInfo) throws IOException {
    if (blockInfo != null) {
      Map<NetAddress, Long> storageDirIds = blockInfo.getStorageDirIds();
      NetAddress workerNetAddress = mWorkerClient.getNetAddress();
      Long storageDirId = storageDirIds.get(workerNetAddress);
      if (storageDirId != null) {
        return getLocalBlockFilePath(storageDirId, blockInfo.getBlockId());
      }
    }
    return null;
  }

  /**
   * Get path of the block file in certain StorageDir
   * 
   * @param storageDirId the id of the StorageDir which contains the block
   * @param blockId the id of the block
   * @return the path of the block file
   * @throws IOException
   */
  synchronized String getLocalBlockFilePath(long storageDirId, long blockId) throws IOException {
    String dirPath = mIdToWorkerDirPath.get(storageDirId);
    if (dirPath != null) {
      String dataFolder = getLocalDataFolder();
      return CommonUtils.concat(dirPath, dataFolder, blockId);
    }
    return null;
  }

  /**
   * Get the RawTable by id
   * 
   * @param id the id of the raw table
   * @return the RawTable
   * @throws IOException
   */
  public synchronized RawTable getRawTable(int id) throws IOException {
    ClientRawTableInfo clientRawTableInfo = mMasterClient.user_getClientRawTableInfo(id, "");
    return new RawTable(this, clientRawTableInfo);
  }

  /**
   * Get the RawTable by path
   * 
   * @param path the path of the raw table
   * @return the RawTable
   * @throws IOException
   */
  public synchronized RawTable getRawTable(TachyonURI path) throws IOException {
    validateUri(path);
    ClientRawTableInfo clientRawTableInfo =
        mMasterClient.user_getClientRawTableInfo(-1, path.getPath());
    return new RawTable(this, clientRawTableInfo);
  }

  /**
   * @return the local root data folder
   * @throws IOException
   */
  synchronized String getLocalDataFolder() throws IOException {
    return mWorkerClient.getDataFolder();
  }

  /**
   * @return the address of the UnderFileSystem
   * @throws IOException
   */
  public synchronized String getUfsAddress() throws IOException {
    return mMasterClient.user_getUfsAddress();
  }

  /**
   * @return URI of the root of the filesystem
   */
  @Override
  public synchronized TachyonURI getUri() {
    String scheme = CommonConf.get().USE_ZOOKEEPER ? Constants.SCHEME_FT : Constants.SCHEME;
    String authority = mMasterAddress.getHostName() + ":" + mMasterAddress.getPort();
    return new TachyonURI(scheme, authority, TachyonURI.SEPARATOR);
  }
  
  /**
   * Returns the userId of the master client. This is only used for testing.
   * 
   * @return the userId of the master client
   * @throws IOException
   */
  long getUserId() throws IOException {
    return mMasterClient.getUserId();
  }

  /**
   * Get information of StorageDirs on worker.
   * 
   * @return information of all the StorageDirs on work
   * @throws IOException
   */
  public synchronized List<WorkerDirInfo> getWorkerDirInfos() throws IOException {
    return mWorkerClient.getWorkerDirInfos();
  }

  /**
   * Get path of specified StorageDir
   * 
   * @param storageDirId the id of the StorageDir
   * @return path of the StorageDir
   */
  public String getWorkerDirPath(long storageDirId) {
    return mIdToWorkerDirPath.get(storageDirId);
  }

  /**
   * @return all the works' info
   * @throws IOException
   */
  public synchronized List<ClientWorkerInfo> getWorkersInfo() throws IOException {
    return mMasterClient.getWorkersInfo();
  }

  /**
   * @return true if there is a local worker, false otherwise
   * @throws IOException
   */
  public synchronized boolean hasLocalWorker() throws IOException {
    return mWorkerClient.isLocal();
  }

  /**
   * Used to initialize file system of StorageDirs
   * 
   * @param workerDirInfos information of StorageDirs on the worker
   * @throws IOException
   */
  private void initializeDirFS(List<WorkerDirInfo> workerDirInfos) throws IOException {
    if (workerDirInfos == null) {
      return;
    }
    for (WorkerDirInfo dirInfo : workerDirInfos) {
      long storageDirId = dirInfo.getStorageDirId();
      mIdToWorkerDirPath.put(storageDirId, dirInfo.getDirPath());

      UnderFileSystem fs;
      try {
        fs =
            UnderFileSystem.get(dirInfo.getDirPath(),
                CommonUtils.byteArrayToObject(dirInfo.getConf()));
      } catch (ClassNotFoundException e) {
        throw new IOException(e.getMessage());
      }
      mIdToDirFS.put(storageDirId, fs);
    }
    return;
  }

  /**
   * @return true if this client is connected to master, false otherwise
   */
  public synchronized boolean isConnected() {
    return mMasterClient.isConnected();
  }

  /**
   * @param fid the file id
   * @return true if the file is a directory, false otherwise
   */
  synchronized boolean isDirectory(int fid) {
    return mIdToClientFileInfo.get(fid).isFolder;
  }

  /**
   * If the <code>path</code> is a directory, return all the direct entries in it. If the
   * <code>path</code> is a file, return its ClientFileInfo.
   * 
   * @param path the target directory/file path
   * @return A list of ClientFileInfo, null if the file or folder does not exist.
   * @throws IOException
   */
  @Override
  public synchronized List<ClientFileInfo> listStatus(TachyonURI path) throws IOException {
    validateUri(path);
    return mMasterClient.listStatus(path.getPath());
  }

  /**
   * Lock a block in the current TachyonFS.
   * 
   * @param blockInfo The information of the block to lock.
   * @param blockLockId The block lock id of the block of lock. <code>blockLockId</code> must be
   *        non-negative.
   * @return the Id of the StorageDir in which the block is locked.
   */
  synchronized long lockBlock(ClientBlockInfo blockInfo, int blockLockId) throws IOException {
    if (blockInfo != null) {
      Map<NetAddress, Long> storageDirIds = blockInfo.getStorageDirIds();
      NetAddress workerNetAddress = mWorkerClient.getNetAddress();
      Long storageDirId = storageDirIds.get(workerNetAddress);
      if (storageDirId != null) {
        return lockBlock(storageDirId, blockInfo.getBlockId(), blockLockId);
      }
    }
    return StorageDirId.unknownId();
  }

  /**
   * Lock a block in certain StorageDir in the current TachyonFS.
   * 
   * @param storageDirId the id of the StorageDir which contains the block.
   * @param blockId The id of the block to lock. <code>blockId</code> must be positive.
   * @param blockLockId The block lock id of the block of lock. <code>blockLockId</code> must be
   *        non-negative.
   * @return the Id of the StorageDir in which the block is locked
   * @throws IOException
   */
  synchronized long lockBlock(long storageDirId, long blockId, int blockLockId)
      throws IOException {
    if (blockId <= 0 || blockLockId < 0) {
      return StorageDirId.unknownId();
    }

    if (mLockedBlockIds.containsKey(blockId)) {
      mLockedBlockIds.get(blockId).add(blockLockId);
      return mLockedBlockIdToStorageDirId.get(blockId);
    }

    if (!mWorkerClient.isLocal()) {
      return StorageDirId.unknownId();
    }
    long storageDirIdLocked =
        mWorkerClient.lockBlock(mMasterClient.getUserId(), storageDirId, blockId);

    if (!StorageDirId.isUnknown(storageDirIdLocked)) {
      Set<Integer> lockIds = new HashSet<Integer>(4);
      lockIds.add(blockLockId);
      mLockedBlockIds.put(blockId, lockIds);
      mLockedBlockIdToStorageDirId.put(blockId, storageDirIdLocked);
    }
    return storageDirIdLocked;
  }

  /**
   * Creates a folder.
   * 
   * @param path the path of the folder to be created
   * @param recursive Creates necessary parent folders if true, not otherwise.
   * @return true if the folder is created successfully or already existing. false otherwise.
   * @throws IOException
   */
  @Override
  public synchronized boolean mkdirs(TachyonURI path, boolean recursive) throws IOException {
    validateUri(path);
    return mMasterClient.user_mkdirs(path.getPath(), recursive);
  }

  /** Alias for setPinned(fid, true). */
  public synchronized void pinFile(int fid) throws IOException {
    setPinned(fid, true);
  }

 /**
  * Frees in memory file or folder
  * @param fileId The id of the file / folder. If it is not -1, path parameter is ignored.
  *        Otherwise, the method uses the path parameter.
  * @param path The path of the file / folder. It could be empty iff id is not -1.
  * @param recursive If fileId or path represents a non-empty folder, free the folder recursively
  *        or not
  * @return true if in-memory free successfully, false otherwise.
  * @throws IOException
  */
  @Override
  public synchronized boolean freepath(int fileId, TachyonURI path, boolean recursive)
      throws IOException {
    validateUri(path);
    return mMasterClient.user_freepath(fileId, path.getPath(), recursive);
  }

  /**
   * Release space to some StorageDir.
   * 
   * @param storageDirId the id of the StorageDir which the space will be release to
   * @param releaseSpaceBytes the size of the space to be released in bytes
   */
  public synchronized void releaseSpace(long storageDirId, long releaseSpaceBytes) {
    if (mIdToAvailableSpaceBytes.containsKey(storageDirId)) {
      long availableSpaceBytes = mIdToAvailableSpaceBytes.get(storageDirId);
      mIdToAvailableSpaceBytes.put(storageDirId, availableSpaceBytes + releaseSpaceBytes);
    } else {
      LOG.warn("Unknown StorageDir! ID:" + storageDirId);
    }
  }

  /**
   * Promote block file back to the top StorageTier, after the block file is accessed.
   * 
   * @param blockInfo information of the block which will be promoted
   * @return true if success, false otherwise
   * @throws IOException
   */
  public boolean promoteBlock(ClientBlockInfo blockInfo) throws IOException {
    Map<NetAddress, Long> storageDirIds = blockInfo.getStorageDirIds();
    NetAddress workerNetAddress = mWorkerClient.getNetAddress();
    Long storageDirId = storageDirIds.get(workerNetAddress);

    if (storageDirId != null) {
      return promoteBlock(storageDirId, blockInfo.getBlockId());
    }
    return false;
  }

  /**
   * Promote block file back to the top StorageTier, after the block file is accessed.
   * 
   * @param storageDirId the id of the StorageDir which contains the block
   * @param blockId the id of the block
   * @return true if success, false otherwise
   * @throws IOException
   */
  public boolean promoteBlock(long storageDirId, long blockId) throws IOException {
    if (mWorkerClient.isLocal()) {
      return mWorkerClient.promoteBlock(mMasterClient.getUserId(), storageDirId, blockId);
    }
    return false;
  }

  /**
   * Renames a file or folder to another path.
   * 
   * @param fileId The id of the source file / folder. If it is not -1, path parameter is ignored.
   *        Otherwise, the method uses the srcPath parameter.
   * @param srcPath The path of the source file / folder. It could be empty iff id is not -1.
   * @param dstPath The path of the destination file / folder. It could be empty iff id is not -1.
   * @return true if renames successfully, false otherwise.
   * @throws IOException
   */
  @Override
  public synchronized boolean rename(int fileId, TachyonURI srcPath, TachyonURI dstPath)
      throws IOException {
    validateUri(srcPath);
    validateUri(dstPath);
    return mMasterClient.user_rename(fileId, srcPath.getPath(), dstPath.getPath());
  }

  /**
   * Report the lost file to master
   * 
   * @param fileId the lost file id
   * @throws IOException
   */
  public synchronized void reportLostFile(int fileId) throws IOException {
    mMasterClient.user_reportLostFile(fileId);
  }

  /**
   * Request the dependency's needed files
   * 
   * @param depId the dependency id
   * @throws IOException
   */
  public synchronized void requestFilesInDependency(int depId) throws IOException {
    mMasterClient.user_requestFilesInDependency(depId);
  }

  /**
   * Try to request space from worker. Only works when a local worker exists.
   * 
   * @param requestSpaceBytes size to request in bytes
   * @return the id of the StorageDir that space is allocated in
   * @throws IOException
   */
  public synchronized long requestSpace(long requestSpaceBytes) throws IOException {
    if (!hasLocalWorker()) {
      return StorageDirId.unknownId();
    }
    long storageDirId = getFromAvaliableSpace(requestSpaceBytes);
    if (storageDirId != StorageDirId.unknownId()) {
      return storageDirId;
    } else {
      long availableSpaceBytes = 0;
      if (mIdToAvailableSpaceBytes.containsKey(storageDirId)) {
        availableSpaceBytes = mIdToAvailableSpaceBytes.get(storageDirId);
      }
      for (int attempt = 0; attempt < FAILED_SPACE_REQUEST_LIMITS; attempt ++) {
        long toRequestSpaceBytes = Math.max(requestSpaceBytes, mUserQuotaUnitBytes);
        storageDirId = 
            mWorkerClient.requestSpace(mMasterClient.getUserId(), toRequestSpaceBytes);
        if (!StorageDirId.isUnknown(storageDirId)) {
          availableSpaceBytes += toRequestSpaceBytes;
          if (availableSpaceBytes >= requestSpaceBytes) {
            mIdToAvailableSpaceBytes.put(storageDirId, availableSpaceBytes - requestSpaceBytes);
            return storageDirId;
          } else {
            mIdToAvailableSpaceBytes.put(storageDirId, availableSpaceBytes);
          }
        }
      }
      return StorageDirId.unknownId();
    }
  }

  /**
   * Try to request space from certain StorageDir on worker. Only works when a local worker exists.
   * 
   * @param storageDirId the id of the StorageDir that space will be allocated in
   * @param requestSpaceBytes size to request in bytes
   * @return true if success, false otherwise
   * @throws IOException
   */
  public synchronized boolean requestSpace(long storageDirId, long requestSpaceBytes)
      throws IOException {
    if (!hasLocalWorker()) {
      return false;
    }
    if (getFromAvaliableSpace(storageDirId, requestSpaceBytes)) {
      return true;
    } else {
      long availableSpaceBytes = 0;
      if (mIdToAvailableSpaceBytes.containsKey(storageDirId)) {
        availableSpaceBytes = mIdToAvailableSpaceBytes.get(storageDirId);
      }
      for (int attempt = 0; attempt < FAILED_SPACE_REQUEST_LIMITS; attempt ++) {
        long toRequestSpaceBytes =
            Math.max(requestSpaceBytes - availableSpaceBytes, mUserQuotaUnitBytes);
        boolean reqResult = 
            mWorkerClient.requestSpace(mMasterClient.getUserId(), storageDirId,
                toRequestSpaceBytes);
        if (reqResult) {
          availableSpaceBytes += toRequestSpaceBytes;
          if (availableSpaceBytes >= requestSpaceBytes) {
            mIdToAvailableSpaceBytes.put(storageDirId, availableSpaceBytes - requestSpaceBytes);
            return true;
          } else {
            mIdToAvailableSpaceBytes.put(storageDirId, availableSpaceBytes);
          }
        }
      }
      return false;
    }
  }

  /**
   * Sets the "pinned" flag for the given file. Pinned files are never evicted by Tachyon until they
   * are unpinned.
   * 
   * Calling setPinned() on a folder will recursively set the "pinned" flag on all of that folder's
   * children. This may be an expensive operation for folders with many files/subfolders.
   */
  public synchronized void setPinned(int fid, boolean pinned) throws IOException {
    mMasterClient.user_setPinned(fid, pinned);
  }

  /**
   * Print out the string representation of this Tachyon server address.
   * 
   * @return the string representation like tachyon://host:port or tachyon-ft://host:port
   */
  @Override
  public String toString() {
    return (mZookeeperMode ? Constants.HEADER_FT : Constants.HEADER) + mMasterAddress.toString();
  }

  /**
   * Unlock a block in the current TachyonFS.
   * 
   * @param blockInfo The information of the block to unlock.
   * @param blockLockId The block lock id of the block of unlock. <code>blockLockId</code> must be
   *        non-negative.
   * @return the Id of the StorageDir in which the block is unlocked.
   */
  synchronized long unlockBlock(ClientBlockInfo blockInfo, int blockLockId) throws IOException {
    if (blockInfo != null) {
      Map<NetAddress, Long> storageDirIds = blockInfo.getStorageDirIds();
      NetAddress workerNetAddress = mWorkerClient.getNetAddress();
      Long storageDirId = storageDirIds.get(workerNetAddress);
      if (storageDirId != null) {
        return unlockBlock(storageDirId, blockInfo.getBlockId(), blockLockId);
      }
    }
    return StorageDirId.unknownId();
  }

  /**
   * Unlock a block in the current TachyonFS.
   * 
   * @param storageDirId the id of the StorageDir which contains the block
   * @param blockId The id of the block to unlock. <code>blockId</code> must be positive.
   * @param blockLockId The block lock id of the block of unlock. <code>blockLockId</code> must be
   *        non-negative.
   * @return the Id of the StorageDir in which the block is unlocked.
   */
  synchronized long unlockBlock(long storageDirId, long blockId, int blockLockId)
      throws IOException {
    if (blockId <= 0 || blockLockId < 0) {
      return StorageDirId.unknownId();
    }

    if (!mLockedBlockIds.containsKey(blockId)) {
      return StorageDirId.unknownId();
    }
    Set<Integer> lockIds = mLockedBlockIds.get(blockId);
    lockIds.remove(blockLockId);

    if (!lockIds.isEmpty()) {
      return mLockedBlockIdToStorageDirId.get(blockId);
    } else {
      mLockedBlockIdToStorageDirId.remove(blockId);
      mLockedBlockIds.remove(blockId);
    }

    if (!mWorkerClient.isLocal()) {
      return StorageDirId.unknownId();
    }

    return mWorkerClient.unlockBlock(mMasterClient.getUserId(), storageDirId, blockId);
  }

  /** Alias for setPinned(fid, false). */
  public synchronized void unpinFile(int fid) throws IOException {
    setPinned(fid, false);
  }

  /**
   * Update the RawTable's meta data
   * 
   * @param id the raw table's id
   * @param metadata the new meta data
   * @throws IOException
   */
  public synchronized void updateRawTableMetadata(int id, ByteBuffer metadata) throws IOException {
    mMasterClient.user_updateRawTableMetadata(id, metadata);
  }

  /**
   * Validates the given uri, throwing an IOException if the uri is invalid.
   * 
   * @param uri The uri to validate
   */
  private void validateUri(TachyonURI uri) throws IOException {
    TachyonURI thisFs = getUri();
    if (uri == null || (!uri.isPathAbsolute() && !TachyonURI.EMPTY_URI.equals(uri))
        || (uri.hasScheme() && !thisFs.getScheme().equals(uri.getScheme()))
        || (uri.hasAuthority() && !thisFs.getAuthority().equals(uri.getAuthority()))) {
      throw new IOException("Uri " + uri + " is invalid.");
    }
  }
}
