package org.apache.cassandra.hadoop.cafs.core;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class CaFileSystem extends FileSystem {

  private static final Logger logger = LoggerFactory.getLogger(CaFileSystem.class);

  public CaFileSystemStore store;
  private URI uri;
  private Path workingDir;
  private long subBlockSize;

  public CaFileSystem() {
    this.store = new CaFileSystemThriftStore();
  }


  public void initialize(URI uri, Configuration conf) throws IOException {

    super.initialize(uri, conf);

    setConf(conf);
    this.uri = URI.create(uri.getScheme() + "://" + uri.getAuthority());
    this.workingDir = new Path("/user", System.getProperty("user.name")).makeQualified(this);

    logger.info(this.workingDir.toString());
    store.initialize(this.uri, conf);

    subBlockSize = conf.getLong("fs.local.subblock.size", 256L * 1024L);
  }

  public FSDataOutputStream append(Path arg0, int arg1, Progressable arg2)
          throws IOException {
    throw new IOException("Not supported");
  }

  @Override
  public FSDataOutputStream create(Path file, FsPermission permission,
                                   boolean overwrite, int bufferSize, short replication, long blockSize,
                                   Progressable progress) throws IOException {
    INode inode = store.retrieveINode(makeAbsolute(file));
    if (inode != null) {
      if (overwrite) {
        delete(file);
      } else {
        throw new IOException("File already exists: " + file);
      }
    } else {
      Path parent = file.getParent();
      if (parent != null) {
        if (!mkdirs(parent)) {
          throw new IOException("Mkdirs failed to create " + parent.toString());
        }
      }
    }

    CaOutputStream cof = new CaOutputStream(getConf(), store, makeAbsolute(file), permission,
            blockSize, subBlockSize, progress, bufferSize);

    return new FSDataOutputStream(cof, statistics);

  }


  public boolean delete(Path path, boolean recursive) throws IOException {

    logger.debug("Deleting {}, recursive flag: {} ", path, recursive);

    Path absolutePath = makeAbsolute(path);
    INode inode = store.retrieveINode(absolutePath);
    if (inode == null) {
      return false;
    }
    if (inode.isFile()) {
      store.deleteINode(absolutePath);
      store.deleteSubBlocks(inode);
    } else {

      FileStatus[] contents = listStatus(absolutePath);
      if (contents == null) {
        return false;
      }
      if ((contents.length != 0) && (!recursive)) {
        throw new IOException("Directory " + path.toString() + " is not empty.");
      }
      for (FileStatus p : contents) {
        if (!delete(p.getPath(), recursive)) {
          return false;
        }
      }
      store.deleteINode(absolutePath);

    }
    return true;
  }

  @Override
  public boolean delete(Path path) throws IOException {
    return delete(path, true);
  }


  @Override
  public URI getUri() {
    return uri;
  }

  @Override
  public String getName() {
    return getUri().toString();
  }

  @Override
  public Path getWorkingDirectory() {
    return workingDir;
  }

  /**
   * FileStatus for Cassandra file systems. {@inheritDoc}
   */
  @Override
  public FileStatus getFileStatus(Path f) throws IOException {
    INode inode = store.retrieveINode(makeAbsolute(f));
    if (inode == null) {
      throw new FileNotFoundException(f.toString());
    }
    return new CaFileStatus(f.makeQualified(this), inode);
  }

  public FileStatus[] listStatus(Path f) throws IOException {
    Path absolutePath = makeAbsolute(f);
    INode inode = store.retrieveINode(absolutePath);
    if (inode == null) {
      return null;
    }
    if (inode.isFile()) {
      return new FileStatus[]{new CaFileStatus(f.makeQualified(this), inode)};
    }
    ArrayList<FileStatus> ret = new ArrayList<FileStatus>();
    for (Path p : store.listSubPaths(absolutePath)) {
      // we shouldn't list ourselves
      if (p.equals(f))
        continue;

      try {
        FileStatus stat = getFileStatus(p.makeQualified(this));

        ret.add(stat);
      } catch (FileNotFoundException e) {
        logger.warn("No file found for: " + p);
      }
    }
    return ret.toArray(new FileStatus[0]);

  }

  private Path makeAbsolute(Path path) {
    if (path.isAbsolute()) {
      return path;
    }

    return new Path(workingDir, path);

  }

  public boolean mkdirs(Path path, FsPermission permission) throws IOException {
    Path absolutePath = makeAbsolute(path);
    List<Path> paths = new ArrayList<Path>();
    do {
      paths.add(0, absolutePath);
      absolutePath = absolutePath.getParent();
    }
    while (absolutePath != null);

    boolean result = true;

    for (Path p : paths) {
      result &= mkdir(p, permission);
    }
    return result;
  }


  private boolean mkdir(Path path, FsPermission permission) throws IOException {
    Path absolutePath = makeAbsolute(path);
    INode inode = store.retrieveINode(absolutePath);

    if (inode == null) {
      inode = new INode(System.getProperty("user.name", "none"),
              System.getProperty("user.name", "none"),
              permission,
              INode.FileType.DIRECTORY, null);
      store.storeINode(absolutePath, inode);
    } else if (inode.isFile()) {
      throw new IOException(String.format("Can't make directory for path %s since it is a file.", absolutePath));
    }

    return true;
  }

  private INode checkFile(Path path) throws IOException {
    INode inode = store.retrieveINode(makeAbsolute(path));
    if (inode == null) {
      throw new IOException("No such file.");
    }
    if (inode.isDirectory()) {
      throw new IOException("Path " + path + " is a directory.");
    }
    return inode;
  }

  @Override
  public FSDataInputStream open(Path path, int bufferSize) throws IOException {
    INode inode = checkFile(path);
    return new FSDataInputStream(new CaInputStream(getConf(), store, inode, statistics));
  }

  private boolean renameRecursive(Path src, Path dst) throws IOException {
    INode srcINode = store.retrieveINode(src);

    Set<Path> paths = store.listDeepSubPaths(src);

    store.storeINode(dst, srcINode);

    for (Path oldSrc : paths) {
      INode inode = store.retrieveINode(oldSrc);
      if (inode == null) {
        return false;
      }
      String oldSrcPath = oldSrc.toUri().getPath();
      String srcPath = src.toUri().getPath();
      String dstPath = dst.toUri().getPath();
      Path newDst = new Path(oldSrcPath.replaceFirst(srcPath, dstPath));
      store.storeINode(newDst, inode);
      store.deleteINode(oldSrc);
    }

    if (!paths.contains(src))
      store.deleteINode(src);

    return true;
  }

  @Override
  public boolean rename(Path src, Path dst) throws IOException {

    logger.debug("Renaming " + src + " to " + dst);

    Path absoluteSrc = makeAbsolute(src);
    INode srcINode = store.retrieveINode(absoluteSrc);
    if (srcINode == null) {
      // src path doesn't exist
      return false;
    }
    Path absoluteDst = makeAbsolute(dst);
    INode dstINode = store.retrieveINode(absoluteDst);
    if (dstINode != null && dstINode.isDirectory()) {
      absoluteDst = new Path(absoluteDst, absoluteSrc.getName());
      dstINode = store.retrieveINode(absoluteDst);
    }
    if (dstINode != null) {
      // dst path already exists - can't overwrite
      return false;
    }
    Path dstParent = absoluteDst.getParent();
    if (dstParent != null) {
      INode dstParentINode = store.retrieveINode(dstParent);
      if (dstParentINode == null || dstParentINode.isFile()) {
        // dst parent doesn't exist or is a file
        return false;
      }
    }
    return renameRecursive(absoluteSrc, absoluteDst);
  }

  @Override
  public void setWorkingDirectory(Path path) {
    workingDir = makeAbsolute(path);

  }

  @Override
  public boolean isFile(Path path) throws IOException {
    INode inode = store.retrieveINode(makeAbsolute(path));
    if (inode == null) {
      return false;
    }
    return inode.isFile();
  }


}