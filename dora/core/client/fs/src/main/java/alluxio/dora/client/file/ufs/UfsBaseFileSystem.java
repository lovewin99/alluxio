/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.dora.client.file.ufs;

import alluxio.dora.AlluxioURI;
import alluxio.dora.client.file.options.UfsFileSystemOptions;
import alluxio.dora.client.file.FileInStream;
import alluxio.dora.client.file.FileOutStream;
import alluxio.dora.client.file.FileSystem;
import alluxio.dora.client.file.FileSystemContext;
import alluxio.dora.client.file.ListStatusPartialResult;
import alluxio.dora.client.file.URIStatus;
import alluxio.dora.conf.AlluxioConfiguration;
import alluxio.dora.exception.AlluxioException;
import alluxio.dora.exception.runtime.AlluxioRuntimeException;
import alluxio.grpc.CheckAccessPOptions;
import alluxio.grpc.CreateDirectoryPOptions;
import alluxio.grpc.CreateFilePOptions;
import alluxio.grpc.DeletePOptions;
import alluxio.grpc.ErrorType;
import alluxio.grpc.ExistsPOptions;
import alluxio.grpc.FreePOptions;
import alluxio.grpc.GetStatusPOptions;
import alluxio.grpc.JobProgressReportFormat;
import alluxio.grpc.ListStatusPOptions;
import alluxio.grpc.ListStatusPartialPOptions;
import alluxio.grpc.MountPOptions;
import alluxio.grpc.OpenFilePOptions;
import alluxio.grpc.RenamePOptions;
import alluxio.grpc.ScheduleAsyncPersistencePOptions;
import alluxio.grpc.SetAclAction;
import alluxio.grpc.SetAclPOptions;
import alluxio.grpc.SetAttributePOptions;
import alluxio.grpc.UnmountPOptions;
import alluxio.dora.job.JobDescription;
import alluxio.dora.job.JobRequest;
import alluxio.dora.resource.CloseableResource;
import alluxio.dora.security.authorization.AclEntry;
import alluxio.dora.security.authorization.Mode;
import alluxio.dora.underfs.UfsFileStatus;
import alluxio.dora.underfs.UfsManager;
import alluxio.dora.underfs.UfsStatus;
import alluxio.dora.underfs.UnderFileSystem;
import alluxio.dora.underfs.options.CreateOptions;
import alluxio.dora.underfs.options.DeleteOptions;
import alluxio.dora.underfs.options.ListOptions;
import alluxio.dora.underfs.options.MkdirsOptions;
import alluxio.dora.underfs.options.OpenOptions;
import alluxio.dora.util.CommonUtils;
import alluxio.dora.util.ModeUtils;
import alluxio.dora.util.io.PathUtils;
import alluxio.dora.wire.BlockLocationInfo;
import alluxio.dora.wire.FileInfo;
import alluxio.dora.wire.MountPointInfo;
import alluxio.dora.wire.SyncPointInfo;

import com.google.common.base.Preconditions;
import com.google.common.io.Closer;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.concurrent.ThreadSafe;

/**
 * An implementation of the {@link FileSystem} interface that directly interacts with a target
 * UFS. No Alluxio server is involved.
 */
@ThreadSafe
public class UfsBaseFileSystem implements FileSystem {
  private static final Logger LOG = LoggerFactory.getLogger(UfsBaseFileSystem.class);

  /** Used to manage closeable resources. */
  private final Closer mCloser = Closer.create();
  protected final FileSystemContext mFsContext;
  protected final CloseableResource<UnderFileSystem> mUfs;
  private final AlluxioURI mRootUFS;
  protected volatile boolean mClosed = false;

  /**
   * Constructs a new base file system.
   *
   * @param fsContext file system context
   * @param options the ufs file system options
   */
  public UfsBaseFileSystem(FileSystemContext fsContext, UfsFileSystemOptions options) {
    Preconditions.checkNotNull(fsContext);
    Preconditions.checkNotNull(options);
    mFsContext = fsContext;
    String ufsAddress = options.getUfsAddress();
    Preconditions.checkArgument(!ufsAddress.isEmpty(), "ufs address should not be empty");
    mRootUFS = new AlluxioURI(ufsAddress);
    UfsManager.UfsClient ufsClient = new UfsManager.UfsClient(
        () -> UnderFileSystem.Factory.create(ufsAddress, mFsContext.getClusterConf()),
        new AlluxioURI(ufsAddress));
    mUfs = ufsClient.acquireUfsResource();
    mCloser.register(mFsContext);
    mCloser.register(mUfs);
    LOG.debug("Creating file system connecting to ufs address {}", ufsAddress);
  }

  /**
   * Shuts down the FileSystem. Closes all thread pools and resources used to perform operations. If
   * any operations are called after closing the context the behavior is undefined.
   */
  @Override
  public synchronized void close() throws IOException {
    if (!mClosed) {
      mClosed = true;
    }
    mCloser.close();
  }

  @Override
  public synchronized boolean isClosed() {
    return mClosed;
  }

  @Override
  public void checkAccess(AlluxioURI path, CheckAccessPOptions options) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void createDirectory(AlluxioURI path, CreateDirectoryPOptions options) {
    call(() -> {
      // TODO(lu) deal with other options e.g. owner/group
      MkdirsOptions ufsOptions = MkdirsOptions.defaults(mFsContext.getPathConf(path));
      if (options.hasMode()) {
        ufsOptions.setMode(Mode.fromProto(options.getMode()));
      }
      if (options.hasRecursive()) {
        ufsOptions.setCreateParent(options.getRecursive());
      }
      mUfs.get().mkdirs(path.toString(), ufsOptions);
    });
  }

  @Override
  public FileOutStream createFile(AlluxioURI path, CreateFilePOptions options) {
    return callWithReturn(() -> {
      // TODO(lu) deal with other options e.g. owner/group/acl/ensureAtomic
      CreateOptions ufsOptions = CreateOptions.defaults(mFsContext.getPathConf(path));
      if (options.hasMode()) {
        ufsOptions.setMode(Mode.fromProto(options.getMode()));
      }
      if (options.hasRecursive()) {
        ufsOptions.setCreateParent(options.getRecursive());
      }
      return new UfsFileOutStream(mUfs.get().create(path.toString(), ufsOptions));
    });
  }

  @Override
  public void delete(AlluxioURI path, DeletePOptions options) {
    call(() -> {
      String ufsPath = path.toString();
      if (mUfs.get().isFile(ufsPath)) {
        mUfs.get().deleteFile(ufsPath);
        return;
      }
      DeleteOptions ufsOptions = DeleteOptions.defaults();
      if (options.hasRecursive()) {
        ufsOptions.setRecursive(options.getRecursive());
      }
      mUfs.get().deleteDirectory(ufsPath, ufsOptions);
    });
  }

  @Override
  public boolean exists(AlluxioURI path, final ExistsPOptions options) {
    return Boolean.TRUE.equals(callWithReturn(() -> mUfs.get().exists(path.toString())));
  }

  @Override
  public void free(AlluxioURI path, final FreePOptions options) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<BlockLocationInfo> getBlockLocations(AlluxioURI path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<BlockLocationInfo> getBlockLocations(URIStatus status) {
    throw new UnsupportedOperationException();
  }

  @Override
  public AlluxioConfiguration getConf() {
    return mFsContext.getClusterConf();
  }

  @Override
  public URIStatus getStatus(AlluxioURI path) {
    return getStatus(path, GetStatusPOptions.getDefaultInstance());
  }

  @Override
  public URIStatus getStatus(AlluxioURI path, final GetStatusPOptions options) {
    return callWithReturn(() -> transformStatus(mUfs.get().getStatus(path.toString())));
  }

  @Override
  public List<URIStatus> listStatus(AlluxioURI path, final ListStatusPOptions options) {
    return callWithReturn(() -> {
      ListOptions ufsOptions = ListOptions.defaults();
      if (options.hasRecursive()) {
        ufsOptions.setRecursive(options.getRecursive());
      }
      UfsStatus[] ufsStatuses = mUfs.get().listStatus(path.toString(), ufsOptions);
      if (ufsStatuses == null || ufsStatuses.length == 0) {
        return Collections.emptyList();
      }
      return Arrays.stream(ufsStatuses).map(this::transformStatus).collect(Collectors.toList());
    });
  }

  @Override
  public void iterateStatus(AlluxioURI path, final ListStatusPOptions options,
      Consumer<? super URIStatus> action) {
    call(() -> {
      ListOptions ufsOptions = ListOptions.defaults();
      if (options.hasRecursive()) {
        ufsOptions.setRecursive(options.getRecursive());
      }
      UfsStatus[] ufsStatuses = mUfs.get().listStatus(path.toString(), ufsOptions);
      if (ufsStatuses == null || ufsStatuses.length == 0) {
        return;
      }
      Arrays.stream(ufsStatuses).map(this::transformStatus).forEach(action);
    });
  }

  @Override
  public ListStatusPartialResult listStatusPartial(
      AlluxioURI path, final ListStatusPartialPOptions options) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void loadMetadata(AlluxioURI path, final ListStatusPOptions options) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void mount(AlluxioURI alluxioPath, AlluxioURI ufsPath, final MountPOptions options) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void updateMount(AlluxioURI alluxioPath, final MountPOptions options) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Map<String, MountPointInfo> getMountTable(boolean checkUfs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<SyncPointInfo> getSyncPathList() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void persist(final AlluxioURI path, final ScheduleAsyncPersistencePOptions options) {
    throw new UnsupportedOperationException();
  }

  @Override
  public FileInStream openFile(AlluxioURI path, OpenFilePOptions options) {
    return openFile(getStatus(path), options);
  }

  @Override
  public FileInStream openFile(URIStatus status, OpenFilePOptions options) {
    return callWithReturn(() -> {
      // TODO(lu) deal with other options e.g. maxUfsReadConcurrency
      return new UfsFileInStream(offset -> {
        try {
          return mUfs.get().open(status.getUfsPath(), OpenOptions.defaults().setOffset(offset));
        } catch (IOException e) {
          throw AlluxioRuntimeException.from(e);
        }
      }, status.getLength());
    });
  }

  @Override
  public void rename(AlluxioURI src, AlluxioURI dst, RenamePOptions options) {
    call(() -> {
      String srcPath = src.toString();
      String dstPath = dst.toString();
      boolean renamed;
      if (mUfs.get().isFile(srcPath)) {
        renamed = mUfs.get().renameFile(srcPath, dstPath);
      } else {
        renamed = mUfs.get().renameDirectory(srcPath, dstPath);
      }
      if (!renamed) {
        throw new AlluxioRuntimeException(
            Status.FAILED_PRECONDITION,
            String.format("Failed to rename from %s to %s", srcPath, dstPath),
            null,
            ErrorType.External,
            false
        );
      }
    });
  }

  @Override
  public AlluxioURI reverseResolve(AlluxioURI ufsUri) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setAcl(AlluxioURI path, SetAclAction action, List<AclEntry> entries,
      SetAclPOptions options) {
    call(() -> mUfs.get().setAclEntries(path.toString(), entries));
  }

  @Override
  public void setAttribute(AlluxioURI path, SetAttributePOptions options) {
    call(() -> {
      if (options.hasMode()) {
        mUfs.get().setMode(path.toString(), ModeUtils.protoToShort(options.getMode()));
      }
      if (options.hasOwner() && options.hasGroup()) {
        mUfs.get().setOwner(path.toString(), options.getOwner(), options.getGroup());
      } else if (options.hasOwner()) {
        mUfs.get().setOwner(path.toString(), options.getOwner(), null);
      } else if (options.hasGroup()) {
        mUfs.get().setOwner(path.toString(), null, options.getOwner());
      }
      if (options.hasPinned() || options.hasPersisted() || options.hasRecursive()
          || options.hasReplicationMax() || options.hasReplicationMin()
          || options.getXattrCount() != 0) {
        LOG.error("UFS only supports setting mode, owner, and group. Does not support setting {}",
            options);
        throw new UnsupportedOperationException(
            String.format("Cannot set attribute of %s", options));
      }
    });
  }

  /**
   * Starts the active syncing process on an Alluxio path.
   *
   * @param path the path to sync
   */
  @Override
  public void startSync(AlluxioURI path) {
    throw new UnsupportedOperationException();
  }

  /**
   * Stops the active syncing process on an Alluxio path.
   * @param path the path to stop syncing
   */
  @Override
  public void stopSync(AlluxioURI path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void unmount(AlluxioURI path, UnmountPOptions options) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void needsSync(AlluxioURI path) throws IOException, AlluxioException {
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<String> submitJob(JobRequest jobRequest) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean stopJob(JobDescription jobDescription) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getJobProgress(JobDescription jobDescription,
      JobProgressReportFormat format, boolean verbose) {
    throw new UnsupportedOperationException();
  }

  /**
   * Transform UFS file/directory status to client-side status.
   *
   * @param ufsStatus the UFS status to transform
   * @return the client-side status
   */
  private URIStatus transformStatus(UfsStatus ufsStatus) {
    String path = CommonUtils.stripPrefixIfPresent(ufsStatus.getName(), mRootUFS.toString());
    AlluxioURI ufsUri = new AlluxioURI(PathUtils.concatPath(mRootUFS, path));
    FileInfo info = new FileInfo().setName(ufsUri.getName())
        .setPath(path)
        .setFileId(ufsUri.toString().hashCode())
        .setUfsPath(ufsUri.toString())
        .setFolder(ufsStatus.isDirectory())
        .setOwner(ufsStatus.getOwner())
        .setGroup(ufsStatus.getGroup())
        .setMode(ufsStatus.getMode())
        .setCompleted(true);
    if (ufsStatus.getLastModifiedTime() != null) {
      info.setLastModificationTimeMs(ufsStatus.getLastModifiedTime());
    }
    if (ufsStatus.getXAttr() != null) {
      info.setXAttr(ufsStatus.getXAttr());
    }
    if (ufsStatus instanceof UfsFileStatus) {
      UfsFileStatus fileStatus = (UfsFileStatus) ufsStatus;
      info.setLength(fileStatus.getContentLength());
      info.setBlockSizeBytes(fileStatus.getBlockSize());
    } else {
      info.setLength(0);
    }
    return new URIStatus(info);
  }

  /**
   * Gets the UFS Root.
   *
   * @return AlluxioURI of UFS Root
   */
  public AlluxioURI getRootUFS() {
    return mRootUFS;
  }

  private static void call(UfsCallable callable) {
    try {
      callable.call();
    } catch (IOException e) {
      throw AlluxioRuntimeException.from(e);
    }
  }

  private static <T> T callWithReturn(UfsCallableWithReturn<T> callable) {
    try {
      return callable.call();
    } catch (IOException e) {
      throw AlluxioRuntimeException.from(e);
    }
  }

  interface UfsCallable {
    void call() throws IOException;
  }

  interface UfsCallableWithReturn<V> {
    V call() throws IOException;
  }
}
