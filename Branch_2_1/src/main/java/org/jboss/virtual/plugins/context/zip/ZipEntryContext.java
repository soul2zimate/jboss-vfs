/*
* JBoss, Home of Professional Open Source
* Copyright 2006, JBoss Inc., and individual contributors as indicated
* by the @authors tag. See the copyright.txt in the distribution for a
* full listing of individual contributors.
*
* This is free software; you can redistribute it and/or modify it
* under the terms of the GNU Lesser General Public License as
* published by the Free Software Foundation; either version 2.1 of
* the License, or (at your option) any later version.
*
* This software is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
* Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public
* License along with this software; if not, write to the Free
* Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
* 02110-1301 USA, or see the FSF site: http://www.fsf.org.
*/
package org.jboss.virtual.plugins.context.zip;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.jboss.logging.Logger;
import org.jboss.virtual.VFSUtils;
import org.jboss.virtual.plugins.context.AbstractVFSContext;
import org.jboss.virtual.plugins.context.AbstractVirtualFileHandler;
import org.jboss.virtual.plugins.context.DelegatingHandler;
import org.jboss.virtual.plugins.context.ReplacementHandler;
import org.jboss.virtual.plugins.context.jar.JarUtils;
import org.jboss.virtual.plugins.copy.AbstractCopyMechanism;
import org.jboss.virtual.spi.*;

/**
 * <tt>ZipEntryContext</tt> implements a {@link org.jboss.virtual.spi.VFSContext}
 * that exposes a zip archive as a virtual file system.
 *
 * Zip archive can be in a form of a file or a stream.
 *
 * Nested archives are processed through this same class.
 * By default nested archives are cached in memory and mounted as new
 * instances of <tt>ZipEntryContext</tt> with <tt>ZipStreamWrapper</tt> as a source.
 * If system property <em>jboss.vfs.forceCopy=true</em> is specified,
 * or URL query parameter <em>forceCopy=true</em> is present,
 * nested archives are extracted into a temp directory before being
 * mounted as new instances of <tt>ZipEntryContext</tt>.
 *
 * In-memory nested archives may consume a lot of memory. To reduce memory footprint
 * at the expense of performance, system property <em>jboss.vfs.optimizeForMemory=true<em>
 * can be set.
 *
 * This context implementation has two modes of releasing file locks.
 * <em>Asynchronous</em> mode is the default one since it is better performant.
 * To switch this to <em>synchronous</em> mode a system property
 * <em>jboss.vfs.forceNoReaper=true</em> can be specified or URL query parameter
 * <em>noReaper=true</em> can be included in context URL.
 *
 * This context implementation is a replacement for
 * {@link org.jboss.virtual.plugins.context.jar.JarContext}.
 *
 * @author <a href="strukelj@parsek.net">Marko Strukelj</a>
 * @author <a href="ales.justin@jboss.org">Ales Justin</a>
 * @author <a href="david.lloyd@jboss.com">David M. Lloyd</a>
 * @version $Revision: 1.0 $
 */
public class ZipEntryContext extends AbstractVFSContext
{
   /** Logger */
   private static final Logger log = Logger.getLogger(ZipEntryContext.class);

   /** Global setting for nested archive processing mode: copy or no-copy (default) */
   private static boolean forceCopy;

   static
   {
      deleteTmpDirContents();

      forceCopy = AccessController.doPrivileged(new CheckForceCopy());

      if (forceCopy)
         log.info("VFS force nested jars copy-mode is enabled.");
   }

   /** The empty bytes const */
   private static final byte[] NO_BYTES = new byte[0];

   /** The constraints set */
   private static final Set<VFSContextConstraints> CONSTRAINTS = Collections.singleton(VFSContextConstraints.CACHEABLE);

   /** Abstracted access to zip archive - either ZipFileWrapper or ZipStreamWrapper */
   private volatile ZipWrapper zipSource;

   /** Entry path representing a context root - archive root is not necessarily a context root */
   private volatile String rootEntryPath = "";

   /** path to zip file - used for lazy ZipWrapper initialization */
   private volatile String filePath = null;

   /** AutoClean signals if zip archive should be deleted after closing the context - true for nested archives */
   private final boolean autoClean;

   /** Registry of everything that zipSource contains */
   private final Map<String, EntryInfo> entries = new ConcurrentHashMap<String, EntryInfo>(16,.75f,4);

   /** Have zip entries been navigated yet */
   private volatile InitializationStatus initStatus = InitializationStatus.NOT_INITIALIZED;

   /** RealURL of this context */
   private volatile URL realURL;

   /**
    * Create a new ZipEntryContext
    *
    * @param rootURL - file or jar:file url
    * @throws URISyntaxException for any URI error
    * @throws java.io.IOException for any error
    */
   public ZipEntryContext(URL rootURL) throws URISyntaxException, IOException
   {
      this(rootURL, false);
   }

   /**
    * Create a new ZipEntryContext
    *
    * @param rootURL - file or jar:file url
    * @param autoClean - true if file represented by rootURL should be deleted after this context is closed
    * @throws URISyntaxException for any URI error
    * @throws java.io.IOException for any error
    */
   public ZipEntryContext(URL rootURL, boolean autoClean) throws URISyntaxException, IOException
   {
      super(VFSUtils.toURI(fixUrl(rootURL)));
      this.autoClean = autoClean;
      init(rootURL, null, null);
   }

   /**
    * Create a new ZipEntryContext to be mounted into another context
    *
    * @param rootURL - url representing this context within another context
    * @param peer - file handler in another context through which this context is being mounted
    * @param localRootUrl - file or jar:file url
    * @throws URISyntaxException for any URI error
    * @throws java.io.IOException for any error
    */
   public ZipEntryContext(URL rootURL, VirtualFileHandler peer, URL localRootUrl) throws URISyntaxException, IOException
   {
      this(rootURL, peer, localRootUrl, false);
   }

   /**
    * Create a new ZipEntryContext to be mounted into another context
    *
    * @param rootURL - url representing this context within another context
    * @param peer - file handler in another context through which this context is being mounted
    * @param localRootUrl - file or jar:file url
    * @param autoClean - true if file represented by localRootURL should be deleted after this context is closed
    * @throws URISyntaxException for any URI error
    * @throws java.io.IOException for any error
    */
   public ZipEntryContext(URL rootURL, VirtualFileHandler peer, URL localRootUrl, boolean autoClean) throws URISyntaxException, IOException
   {
      super(VFSUtils.toURI(fixUrl(rootURL)));
      this.autoClean = autoClean;
      init(localRootUrl, peer, null);
   }

   /**
    * Create a new ZipEntryContext to be mounted into another context
    *
    * @param rootURL - url representing this context within another context
    * @param peer - file handler in another context through which this context is being mounted
    * @param zipWrapper - abstracted zip archive source
    * @param autoClean - true if file represented by localRootURL should be deleted after this context is closed
    * @throws URISyntaxException for any URI error
    * @throws java.io.IOException for any error
    */
   public ZipEntryContext(URL rootURL, VirtualFileHandler peer, ZipWrapper zipWrapper, boolean autoClean) throws URISyntaxException, IOException
   {
      super(VFSUtils.toURI(fixUrl(rootURL)));
      this.autoClean = autoClean;
      init(null, peer, zipWrapper);
   }

   /**
    * Extra initialization in addition to what's inside constructors
    *
    * @param localRootURL the local url
    * @param peer the peer
    * @param zipWrapper zip wrapper
    * @throws URISyntaxException for any URI error
    * @throws java.io.IOException for any error
    */
   private void init(URL localRootURL, VirtualFileHandler peer, ZipWrapper zipWrapper) throws IOException, URISyntaxException
   {
      if (zipWrapper == null)
      {
         if (localRootURL == null)
            throw new IllegalArgumentException("No ZipWrapper specified and localRootURL is null");

         // initialize rootEntryPath and get archive file path
         filePath = initRootAndPath(localRootURL);
      }
      else
      {
         zipSource = zipWrapper;
      }

      setRootPeer(peer);
      String name = getRootURI().getPath();
      int toPos = name.length();

      // cut off any ending slash
      if(name.length() != 0 && name.charAt(name.length()-1) == '/')
         toPos --;

      // name is last path component
      int namePos = name.lastIndexOf("/", toPos-1);
      name = name.substring(namePos+1, toPos);

      // cut off any ending exclamation
      if(name.length() != 0 && name.charAt(name.length()-1) == '!')
         name = name.substring(0, name.length()-1);

      // init initial root EntryInfo that will be overwritten
      // if zip entry exists for rootEntryPath
      entries.put("", new EntryInfo(new ZipEntryHandler(this, null, name, false), null));

      // It's lazy init now
      //initEntries();
   }

   /**
    * Get zip source.
    * Lazy init.
    *
    * @return the zip source
    */
   protected synchronized ZipWrapper getZipSource()
   {
      if (zipSource == null)
      {
         try
         {
            zipSource = createZipSource(filePath);
         }
         catch (Exception e)
         {
            throw new RuntimeException("Failed to initialize ZipWrapper: " + filePath, e);
         }
      }
      return zipSource;
   }

   /**
    * Returns aggregated options.
    *
    * If peer exists, it uses peer context's options as a basis,
    * and overrides them with this context's options.
    *
    * @return map containing aggregated options
    */
   public Options getAggregatedOptions()
   {
      Options aggregatedOptions = createNewOptions();
      VFSContext peerContext = getPeerContext();
      if (peerContext != null)
         aggregatedOptions.merge(peerContext.getOptions());
      aggregatedOptions.merge(super.getOptions()); // put them after peer, possible override
      return aggregatedOptions;
   }

   public ExceptionHandler getExceptionHandler()
   {
      ExceptionHandler eh = super.getExceptionHandler();
      if (eh != null)
         return eh;

      VFSContext peerContext = getPeerContext();
      return peerContext != null ? peerContext.getExceptionHandler() : null;
   }

   /**
    * Create zip source.
    *
    * @param rootPath the root path
    * @return zip entry wrapper
    * @throws IOException for any error
    */
   protected ZipWrapper createZipSource(String rootPath) throws Exception
   {
      File file = null;
      String relative = null;
      URI uri = new URI("file:" + rootPath); // we expect a file
      File fp = new File(uri);
      if (fp.exists())
      {
         file = fp;
      }
      else
      {
         File curr = fp;
         relative = fp.getName();
         while ((curr = curr.getParentFile()) != null)
         {
            if (curr.exists())
            {
               file = curr;
               break;
            }
            else
            {
               relative = curr.getName() + "/" + relative;
            }
         }
      }

      if (file == null)
         throw new IOException("VFS file does not exist: " + rootPath);

      RealURLInfo urlInfo = new RealURLInfo(file);

      if (relative != null)
      {
         ZipWrapper wrapper = findEntry(new FileInputStream(file), relative, urlInfo);
         realURL = urlInfo.toURL();
         return wrapper;
      }
      else
      {
         Options aggregatedOptions = getAggregatedOptions();
         boolean noReaper = aggregatedOptions.getBooleanOption(VFSUtils.NO_REAPER_QUERY);
         realURL = urlInfo.toURL();
         boolean isAutoClean = autoClean || aggregatedOptions.getBooleanOption(VFSUtils.IS_TEMP_FILE);
         return new ZipFileWrapper(file, isAutoClean, noReaper);
      }
   }

   /**
    * Find exact entry.
    * Use recursion on relative path.
    *
    * @param is the input stream
    * @param relative relative path
    * @param urlInfo url info
    * @return zip wrapper instance
    * @throws IOException for any error
    */
   protected ZipWrapper findEntry(InputStream is, String relative, RealURLInfo urlInfo) throws IOException
   {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      VFSUtils.copyStreamAndClose(is, baos);
      ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

      // first we need to find best/longest name
      ZipInputStream zis = new ZipInputStream(bais);
      ZipEntry entry;
      String longestNameMatch = null;
      while((entry = zis.getNextEntry()) != null)
      {
         String entryName = entry.getName();
         String match = entryName;
         if (entry.isDirectory())
            match = match.substring(0, match.length() - 1);

         if (relative.startsWith(match))
         {
            if (match.equals(relative))
            {
               if (entry.isDirectory())
               {
                  this.rootEntryPath = relative;
                  return new ZipDirWrapper(zis, entryName, System.currentTimeMillis(), bais);
               }
               else if (JarUtils.isArchive(match) == false)
               {
                  return new ZipEntryWrapper(zis, entryName, System.currentTimeMillis());
               }
               else
               {
                  return new ZipStreamWrapper(zis, entryName, System.currentTimeMillis());
               }
            }

            if (longestNameMatch == null || longestNameMatch.length() < entryName.length())
            {
               longestNameMatch = entryName; // keep entry name
            }
         }
      }
      if (longestNameMatch == null)
         throw new IllegalArgumentException("Cannot find entry: " + is + ", " + relative);

      // do recursion on relative
      bais.reset();
      zis = new ZipInputStream(bais);
      while((entry = zis.getNextEntry()) != null)
      {
         String entryName = entry.getName();
         if (entryName.equals(longestNameMatch))
         {
            if (urlInfo != null)
               urlInfo.relativePath = longestNameMatch;

            relative = relative.substring(longestNameMatch.length() + 1);
            return findEntry(zis, relative, null);
         }
      }
      throw new IllegalArgumentException("No such entry: " + is + ", " + relative);
   }

   public Set<VFSContextConstraints> getConstraints()
   {
      return CONSTRAINTS;
   }

   /**
    * Returns archive file name - if this is a top-level ZipEntryContext.
    * Otherwise it returns the last component of URL.
    *
    * @return name
    */
   public String getName()
   {
      VirtualFileHandler peer = getRootPeer();
      if (peer != null)
         return peer.getName();
      else
         return getZipSource().getName();
   }

   /**
    * Iterate through zip archive entries, compose a tree structure of archive's content
    *
    * @throws URISyntaxException for any URI error
    * @throws java.io.IOException for any error
    */
   private synchronized void initEntries() throws IOException, URISyntaxException
   {
      boolean trace = log.isTraceEnabled();

      // we're using a two phase approach - we first select the relevant ones
      // then we order these by name and only then we process them
      // this way we ensure that parent entries are processed before child entries

      Map<String, ZipEntry> relevant = new HashMap<String, ZipEntry>();
      ZipWrapper zipSource = getZipSource();
      zipSource.acquire();
      try
      {
         Enumeration<? extends ZipEntry> zipEntries = zipSource.entries();
         // zoom-in on entries under rootEntryPath - ignoring the rest
         while (zipEntries.hasMoreElements())
         {
            ZipEntry ent = zipEntries.nextElement();
            if (ent.getName().startsWith(rootEntryPath))
            {
               relevant.put(ent.getName(), ent);
            }
         }

         Map<String, ZipEntry> orderedRelevant = new TreeMap<String, ZipEntry>(relevant);
         for (Map.Entry<String, ZipEntry> entry : orderedRelevant.entrySet())
         {
            ZipEntry ent = entry.getValue();
            String fullName = ent.getName().substring(rootEntryPath.length());

            String[] split = splitParentChild(fullName);
            String parentPath = split[0];
            String name = split[1];

            EntryInfo ei = null;
            if ("".equals(name) == false)
            {
               ei = entries.get(parentPath);
               if (ei == null)
                  ei = makeDummyParent(parentPath);
            }
            AbstractVirtualFileHandler parent = ei != null ? ei.handler : null;

            // it's a nested jar
            if (ent.isDirectory() == false && JarUtils.isArchive(ent.getName()))
            {
               boolean useCopyMode = forceCopy;
               if (useCopyMode == false)
                  useCopyMode = getAggregatedOptions().getBooleanOption(VFSUtils.USE_COPY_QUERY);

               DelegatingHandler delegator;

               if (useCopyMode)
               {
                  File dest = null;
                  String entryName = ent.getName();
                  String path = null;

                  VFSContext context = getPeerContext();
                  if (context != null)
                  {
                     path = getPath(context, entryName);
                     TempInfo ti = context.getTempInfo(path);
                     if (ti != null && ti.isValid())
                     {
                        dest = ti.getTempFile();
                     }
                  }

                  boolean createNewTempInfo = (dest == null || dest.exists() == false);

                  if (createNewTempInfo)
                  {
                     // extract it to temp dir
                     String tempName = getTempFileName(entryName);
                     TempStore store = (context != null) ? context.getTempStore() : null;
                     if (store != null)
                     {
                        File tempDir = store.createTempFolder(zipSource.getName(), ent.getName());
                        if (tempDir != null)
                           dest = new File(tempDir, tempName);
                     }
                     if (dest == null)
                        dest = new File(getTempDir() + "/" + tempName);
                     dest.deleteOnExit();

                     if (trace)
                     {
                        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
                        log.trace("Dest: " + dest + ", Stack-trace:\n" + Arrays.toString(stackTraceElements));
                     }

                     // ensure parent exists
                     dest.getParentFile().mkdirs();

                     InputStream is = zipSource.openStream(ent);
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(dest));
                     VFSUtils.copyStreamAndClose(is, os);
                  }

                  // mount another instance of ZipEntryContext
                  delegator = mountZipFile(parent, name, dest);

                  if (context != null && path != null && createNewTempInfo)
                     context.addTempInfo(new ZipEntryTempInfo(path, dest, delegator, this));
               }
               else
               {
                  // mount another instance of ZipEntryContext
                  delegator = mountZipStream(parent, name, zipSource.openStream(ent));
               }

               entries.put(delegator.getLocalPathName(), new EntryInfo(delegator, ent));
               addChild(parent, delegator);
            }
            else
            {
               ZipEntryHandler wrapper = new ZipEntryHandler(this, parent, name, ent.isDirectory() == false);
               entries.put(wrapper.getLocalPathName(), new EntryInfo(wrapper, ent));
            }
         }
      }
      finally
      {
         zipSource.release();
      }
   }

   /**
    * Get temp path.
    *
    * @param peer the peer vfs context
    * @param entryName the zip entry name
    * @return full temp path
    */
   protected String getPath(VFSContext peer, String entryName)
   {
      URI peerURI = peer.getRootURI();
      String peerString = VFSUtils.stripProtocol(peerURI);
      String thisString = VFSUtils.stripProtocol(getRootURI());
      return thisString.substring(peerString.length()) + entryName;
   }

   /**
    * Perform initialization only if it hasn't been done yet
    */
   private synchronized void ensureEntries()
   {
      if (initStatus != InitializationStatus.NOT_INITIALIZED)
         return;

      try
      {
         initStatus = InitializationStatus.INITIALIZING;
         initEntries();
         initStatus = InitializationStatus.INITIALIZED;
      }
      catch (Exception ex)
      {
         ExceptionHandler eh = getExceptionHandler();
         if (eh != null)
            eh.handleZipEntriesInitException(ex, getZipSource().getName());
         else
            throw new RuntimeException("Failed to read zip file: " + getZipSource(), ex);
      }
      finally
      {
         if (initStatus == InitializationStatus.INITIALIZING)
            initStatus = InitializationStatus.NOT_INITIALIZED;
      }
   }

   /**
    * Reset init status.
    */
   synchronized void resetInitStatus()
   {
      if (initStatus != InitializationStatus.NOT_INITIALIZED)
      {
         EntryInfo rootInfo = entries.get("");
         entries.clear();
         entries.put("", rootInfo);

         initStatus = InitializationStatus.NOT_INITIALIZED;
      }
   }

   /**
    * Mount ZipEntryContext created around extracted nested archive
    *
    * @param parent the parent
    * @param name the name
    * @param file the file
    * @return mounted delegate
    * @throws IOException for any error
    * @throws URISyntaxException for any URI syntax error
    */
   protected DelegatingHandler mountZipFile(VirtualFileHandler parent, String name, File file) throws IOException, URISyntaxException
   {
      DelegatingHandler delegator = new DelegatingHandler(this, parent, name);
      URL fileUrl = file.toURI().toURL();
      URL delegatorUrl = fileUrl;

      if (parent != null)
         delegatorUrl = getChildURL(parent, name);

      delegatorUrl = setOptionsToURL(delegatorUrl);
      ZipEntryContext ctx = new ZipEntryContext(delegatorUrl, delegator, fileUrl, true);

      VirtualFileHandler handler = ctx.getRoot();
      delegator.setDelegate(handler);

      return delegator;
   }

   /**
    * Mount ZipEntryContext created around ZipStreamWrapper
    *
    * @param parent the parent
    * @param name the name
    * @param zipStream the zip stream
    * @return mounted delegate
    * @throws IOException for any error
    * @throws URISyntaxException for any URI syntax error
    */
   protected DelegatingHandler mountZipStream(VirtualFileHandler parent, String name, InputStream zipStream) throws IOException, URISyntaxException
   {
      DelegatingHandler delegator = new DelegatingHandler(this, parent, name);
      long lastModified = (parent != null) ? parent.getLastModified() : System.currentTimeMillis();
      ZipStreamWrapper wrapper = new ZipStreamWrapper(zipStream, name, lastModified);

      URL delegatorUrl = null;

      if (parent != null)
         delegatorUrl = getChildURL(parent, name);

      delegatorUrl = setOptionsToURL(delegatorUrl);
      ZipEntryContext ctx = new ZipEntryContext(delegatorUrl, delegator, wrapper, false);

      VirtualFileHandler handler = ctx.getRoot();
      delegator.setDelegate(handler);

      return delegator;
   }

   /**
    * Zip archives sometimes don't contain directory entries - only leaf entries
    *
    * @param parentPath the parent path
    * @return entry info
    * @throws IOException for any error
    */
   private EntryInfo makeDummyParent(String parentPath) throws IOException
   {
      // get grand parent first
      String [] split = splitParentChild(parentPath);
      String grandPa = split[0];

      EntryInfo eiParent = entries.get(grandPa);
      if(eiParent == null)
         eiParent = makeDummyParent(grandPa);

      ZipEntryHandler handler = new ZipEntryHandler(this, eiParent.handler, split[1], false);
      EntryInfo ei = new EntryInfo(handler, null);
      entries.put(parentPath, ei);
      return ei;
   }

   /**
    * Initialize rootEntryPath and return archive file path
    *
    * @param localRootUrl local root url
    * @return file path
    */
   private String initRootAndPath(URL localRootUrl)
   {
      String filePath = localRootUrl.toString();
      String zipPath = filePath;

      int pos = filePath.indexOf("!");
      if(pos > 0)
      {
         zipPath = filePath.substring(0, pos);
         rootEntryPath = filePath.substring(pos+2);
         if(rootEntryPath.length() != 0)
            rootEntryPath += "/";
      }

      // find where url protocol ends - i.e. jar:file:/ ...
      pos= zipPath.indexOf(":/");
      filePath = zipPath.substring(pos + 1);

      // cut out url query part if present
      int queryStart = filePath.indexOf("?");
      if (queryStart != -1)
         filePath = filePath.substring(0, queryStart);

      return filePath;
   }

   /**
    * If archive has been modified, clear <em>entries</em> and re-initialize.
    * If not initialized yet, initialize it.
    */
   private synchronized void checkIfModified()
   {
      // TODO: if zipSource represents a nested archive we should maybe delegate lastModified to its parent
      if (initStatus == InitializationStatus.NOT_INITIALIZED)
      {
         ensureEntries();
      }
      else if (initStatus == InitializationStatus.INITIALIZED && getZipSource().hasBeenModified())
      {
         EntryInfo rootInfo = entries.get("");
         entries.clear();
         entries.put("", rootInfo);

         if (getZipSource().exists())
         {
            try
            {
               initEntries();
            }
            catch(Exception ignored)
            {
               log.warn("IGNORING: Failed to reinitialize context: " + getRootURI(), ignored);
            }
         }
      }
   }

   /**
    * Returns this context's root
    *
    * @return root handler
    */
   public VirtualFileHandler getRoot()
   {
      return entries.get("").handler;
   }

   /**
    * Find a child with a given name and a given parent
    *
    * @param parent parent handler
    * @param name  name of the child
    * @return child handler or null if not found
    */
   VirtualFileHandler getChild(ZipEntryHandler parent, String name)
   {
      if (parent == null)
         throw new IllegalArgumentException("Null parent");

      checkIfModified();

      String pathName = parent.getLocalPathName();
      if("".equals(pathName))
         pathName = name;
      else
         pathName = pathName + "/" + name;

      EntryInfo ei = entries.get(pathName);
      if(ei != null)
         return ei.handler;

      return null;
   }

   /**
    * Returns a list of children for a given parent
    *
    * @param parent parent handler
    * @param ignoreErrors true if errors should be silently ignored
    * @return list of handlers representing children of the given parent
    * @throws IOException for any error
    */
   public List<VirtualFileHandler> getChildren(VirtualFileHandler parent, boolean ignoreErrors) throws IOException
   {
      if (parent == null)
         throw new IllegalArgumentException("Null parent");

      checkIfModified();
      if(parent instanceof AbstractVirtualFileHandler)
      {
         AbstractVirtualFileHandler parentHandler  = (AbstractVirtualFileHandler) parent;
         EntryInfo parentEntry = entries.get(parentHandler.getLocalPathName());
         if (parentEntry != null)
         {
            if (parentEntry.handler instanceof DelegatingHandler)
               return parentEntry.handler.getChildren(ignoreErrors);

            return parentEntry.getChildren();
         }
      }
      return Collections.emptyList();
   }

   /**
    * Do delete.
    *
    * @param handler the zip entry handler
    * @param gracePeriod the grace period
    * @return true if delete succeeded
    * @throws IOException for any error
    */
   boolean delete(ZipEntryHandler handler, int gracePeriod) throws IOException
   {
      return getRoot().equals(handler) && getZipSource().delete(gracePeriod);
   }

   /**
    * Close the handler, if it's root.
    *
    * @param handler the handler to close
    */
   void cleanup(ZipEntryHandler handler)
   {
      VirtualFileHandler rootHandler = getRoot();
      if (rootHandler.equals(handler))
      {
         getZipSource().close(); // close == cleanup in zip source impl
      }
   }

   /**
    * Returns lastModified timestamp for a given handler
    *
    * @param handler a handler
    * @return lastModified timestamp
    */
   long getLastModified(ZipEntryHandler handler)
   {
      if (handler == null)
         throw new IllegalArgumentException("Null handler");

      if (getRoot().equals(handler) == false)
         checkIfModified();
      EntryInfo ei = entries.get(handler.getLocalPathName());
      if(ei == null)
         return 0;

      if(ei.entry == null) {
         return getZipSource().getLastModified();
      }

      return ei.entry.getTime();
   }

   /**
    * Returns the size for a given handler
    *
    * @param handler a handler
    * @return size in bytes
    */
   long getSize(ZipEntryHandler handler)
   {
      if (handler == null)
         throw new IllegalArgumentException("Null handler");

      if(getRoot().equals(handler))
         return getZipSource().getSize();

      checkIfModified();

      EntryInfo ei = entries.get(handler.getLocalPathName());
      if(ei == null || ei.entry == null)
         return 0;

      return ei.entry.getSize();
   }

   /**
    * Returns true if entry exists for a given handler
    *
    * @param handler a handler
    * @return true if entry exists
    */
   boolean exists(ZipEntryHandler handler)
   {
      if (handler == null)
         throw new IllegalArgumentException("Null handler");

      if (getRoot().equals(handler))
         return getZipSource().exists();

      checkIfModified();
      EntryInfo ei = entries.get(handler.getLocalPathName());
      return ei != null;
   }

   /**
    * Returns true if handler represents a non-directory entry
    *
    * @param handler the handler
    * @return true if not a directory
    */
   boolean isLeaf(ZipEntryHandler handler)
   {
      if (handler == null)
         throw new IllegalArgumentException("Null handler");

      if (getRoot().equals(handler) == false)
         checkIfModified();

      EntryInfo ei = entries.get(handler.getLocalPathName());
      if (ei == null || ei.entry == null)
         return false;

      return ei.entry.isDirectory() == false;
   }

   /**
    * Is the handler archive.
    *
    * @param handler the handler
    * @return true if archive, false otherwise
    */
   boolean isArchive(ZipEntryHandler handler)
   {
      return getRoot().equals(handler);
   }

   /**
    * Is archive.
    *
    * @param handler the handler
    * @return true if archive
    */
   static boolean isArchive(VirtualFileHandler handler)
   {
      if (handler instanceof ZipEntryHandler && "".equals(handler.getLocalPathName()))
      {
         return true;
      }

      if (handler instanceof DelegatingHandler && ((DelegatingHandler) handler).getDelegate() instanceof ZipEntryHandler)
      {
         return true;
      }

      return false;
   }

   /**
    * Get parent.
    *
    * @param handler the handler to check
    * @return parent handler
    * @throws IOException for any error
    */
   static VirtualFileHandler getParent(VirtualFileHandler handler) throws IOException
   {
      VirtualFileHandler parent = handler.getParent();
      if (parent == null)
      {
         VirtualFileHandler peer = handler.getVFSContext().getRootPeer();
         if (peer != null)
            parent = peer.getParent();
      }
      return parent;
   }

   /**
    * Is nested.
    *
    * @param handler the handler
    * @return true if nested
    * @throws IOException for any error
    */
   static boolean isNested(VirtualFileHandler handler) throws IOException
   {
      VirtualFileHandler parent = getParent(handler);
      while (parent != null)
      {
         if(isArchive(parent))
            return true;

         parent = getParent(parent);
      }
      return false;
   }

   /**
    * Contents of the file represented by a given handler
    *
    * @param handler a handler
    * @return InputStream with entry's content
    * @throws IOException for any error
    */
   InputStream openStream(ZipEntryHandler handler) throws IOException
   {
      if (handler == null)
         throw new IllegalArgumentException("Null handler");

      if (getRoot().equals(handler))
         return getZipSource().getRootAsStream();

      checkIfModified();

      EntryInfo ei = entries.get(handler.getLocalPathName());

      if (ei == null)
      {
         String uriStr;
         try
         {
            uriStr = handler.toURI().toString();
         }
         catch(Exception ex)
         {
            throw new RuntimeException("ASSERTION ERROR - uri generation failed for ZipEntryHandler: " + handler, ex);
         }
         throw new FileNotFoundException(uriStr);
      }

      if(ei.entry == null)
         return new ByteArrayInputStream(NO_BYTES);

      ZipEntry wrapper = new EntryInfoAdapter(ei);
      return getZipSource().openStream(wrapper);
   }

   /**
    * Add a child to a given parent
    *
    * @param parent a parent
    * @param child a child
    */
   void addChild(AbstractVirtualFileHandler parent, AbstractVirtualFileHandler child)
   {
      if (parent == null)
         throw new IllegalArgumentException("Null parent");

      if (child == null)
         throw new IllegalArgumentException("Null child");

      EntryInfo parentEntry = entries.get(parent.getLocalPathName());
      if (parentEntry != null)
         parentEntry.add(child);
      else
         throw new RuntimeException("Parent does not exist: " + parent);
   }

   /**
    * Properly release held resources
    */
   protected void finalize() throws Throwable
   {
      try
      {
         if (zipSource != null)
            zipSource.close();
      }
      catch (Throwable ignored)
      {
         log.debug("IGNORING: Failed to close zip source: " + zipSource, ignored);
      }
      super.finalize();
   }

   /**
    * Replace a current child of the given parent with another one
    *
    * @param parent a parent
    * @param original current child
    * @param replacement new child
    */
   void replaceChild(ZipEntryHandler parent, AbstractVirtualFileHandler original, VirtualFileHandler replacement)
   {
      ensureEntries();
      EntryInfo parentEntry = entries.get(parent.getLocalPathName());
      if (parentEntry != null)
      {
         DelegatingHandler newOne;

         if (replacement instanceof DelegatingHandler)
         {
            newOne = (DelegatingHandler) replacement;
         }
         else
         {
            newOne = new ReplacementHandler(this, parent, original.getName(), replacement);
         }

         synchronized(this)
         {
            parentEntry.replaceChild(original, newOne);

            EntryInfo ei = entries.get(original.getLocalPathName());
            ei.handler = newOne;
            ei.entry = null;
            ei.clearChildren();
         }
      }
      else
      {
         throw new RuntimeException("Parent does not exist: " + parent);
      }
   }

   /**
    * Get RealURL corresponding to root handler
    *
    * @return the real url
    */
   URL getRealURL()
   {
      // make sure realURL has been initialized
      // realURL is initialized when ZipSource is initialized
      getZipSource();
      return realURL;
   }

   /**
    * Get certificates.
    *
    * Note: we *must* first read input stream of the file
    * before we're able to get certificates.
    *
    * @param handler the current handler
    * @return the certificates
    */
   Certificate[] getCertificates(ZipEntryHandler handler)
   {
      EntryInfo ei = entries.get(handler.getLocalPathName());
      return (ei != null) ? ei.getCertificates() : null;
   }

   /**
    *  Internal data structure holding meta information of a virtual file in this context
    */
   static class EntryInfo
   {
      /** a marker */
      static final Certificate[] MARKER = new Certificate[]{};

      /** a handler */
      private AbstractVirtualFileHandler handler;

      /** a <tt>ZipEntry</tt> */
      ZipEntry entry;

      /** the certificates */
      Certificate[] certificates;

      /** a list of children */
      private Map<String, AbstractVirtualFileHandler> children;

      /**
       * EntryInfo constructor
       *
       * @param handler a handler
       * @param entry an entry
       */
      EntryInfo(AbstractVirtualFileHandler handler, ZipEntry entry)
      {
         this.handler = handler;
         this.entry = entry;
      }

      /**
       * Get certificates.
       *
       * @return the certificates
       */
      private Certificate[] getCertificates()
      {
         return (certificates != MARKER) ? certificates : null;
      }

      /**
       * Get children.
       *
       * @return returns a list of children for this handler (by copy)
       */
      public synchronized List<VirtualFileHandler> getChildren()
      {
         if (children == null)
            return Collections.emptyList();

         return new ArrayList<VirtualFileHandler>(children.values());
      }

      /**
       * Replace a child.
       *
       * @param original existing child
       * @param replacement new child
       */
      public synchronized void replaceChild(AbstractVirtualFileHandler original, AbstractVirtualFileHandler replacement)
      {
         if (children != null)
         {
            final String name = original.getName();
            if (children.containsKey(name)) {
               children.put(name, replacement);
            }
         }
      }

      /**
       * Clear the list of children
       */
      public synchronized void clearChildren()
      {
         if (children != null)
            children.clear();
      }

      /**
       * Add a child. If a child with the same name exists already, first remove it.
       *
       * @param child a child
       */
      public synchronized void add(AbstractVirtualFileHandler child)
      {
         if (children == null)
         {
            children = new LinkedHashMap<String, AbstractVirtualFileHandler>();
         }
         children.put(child.getName(), child);
      }
   }

   /**
    * Make sure url protocol is <em>vfszip</em>.
    * Also remove any '!' from URL
    *
    * @param rootURL the root url
    * @return fixed url
    * @throws MalformedURLException for any error
    */
   private static URL fixUrl(URL rootURL) throws MalformedURLException
   {
      String urlStr = rootURL.toExternalForm();
      int pos = urlStr.indexOf("!");
      if (pos != -1)
      {
         String tmp = urlStr.substring(0, pos);
         if (pos < urlStr.length()-1)
            tmp += urlStr.substring(pos+1);
         urlStr = tmp;
      }
      if ("vfszip".equals(rootURL.getProtocol()) == false)
      {
         pos = urlStr.indexOf(":/");
         if (pos != -1)
            urlStr = urlStr.substring(pos);

         return new URL("vfszip" + urlStr);
      }
      return rootURL;
   }

   /**
    * Break to path + name
    *
    * @param pathName the path name
    * @return path tokens
    */
   public static String [] splitParentChild(String pathName)
   {
      if (pathName.startsWith("/"))
         pathName = pathName.substring(1);

      if(pathName.length() == 0)
         return new String [] {null, pathName};

      int toPos = pathName.length();
      if(pathName.charAt(pathName.length()-1) == '/')
         toPos --;

      int delimPos = pathName.lastIndexOf('/', toPos-1);

      String [] ret;
      if(delimPos == -1)
      {
         ret = new String []
         {
            "",
            pathName.substring(delimPos+1, toPos)
         };
      }
      else
      {
         ret = new String []
         {
            pathName.substring(0, delimPos),
            pathName.substring(delimPos+1, toPos)
         };
      }
      return ret;
   }

   /**
    * Temporary files naming scheme
    *
    * @param name the name
    * @return random name
    */
   private static String getTempFileName(String name)
   {
      int delim = name.lastIndexOf("/");
      if (delim != -1)
         name = name.substring(delim+1);
      return UUID.randomUUID().toString().substring(0, 8) + "_" + name;
   }

   /**
    * Use VFS's temp directory and make 'vfs-nested.tmp' sub-directory inside it for our purposes
    *
    * @return temp dir
    */
   private static String getTempDir()
   {
      File dir = new File(AbstractCopyMechanism.getTempDirectory(), "vfs-nested.tmp");
      return dir.toString();
   }

   /**
    * Delete the contents of a temporary directory. Delete first-level files only, don't drill down.
    */
   private static void deleteTmpDirContents()
   {
      try
      {
         File tmpDir = new File(getTempDir());
         File [] files = tmpDir.listFiles();
         if (files != null && files.length > 0)
         {
            for (File file : files)
            {
               if (file.isDirectory() == false && file.isHidden() == false)
                  file.delete();
            }
         }
      }
      catch(Exception ignored)
      {
      }
   }

   /**
    * <tt>PriviligedAction</tt> class for checking a system property
    */
   private static class CheckForceCopy implements PrivilegedAction<Boolean>
   {
      public Boolean run()
      {
         String forceString = System.getProperty(VFSUtils.FORCE_COPY_KEY, "false");
         return Boolean.valueOf(forceString);
      }
   }

   static enum InitializationStatus
   {
      NOT_INITIALIZED,
      INITIALIZING,
      INITIALIZED
   }

   private static class RealURLInfo
   {
      String rootURL;
      String relativePath;

      RealURLInfo(File file) throws MalformedURLException
      {
         String url = file.toURI().toURL().toExternalForm();
         if (url.endsWith("/"))
            url = url.substring(0, url.length()-1);
         rootURL = "jar:" + url + "!/";
      }

      URL toURL() throws MalformedURLException
      {
         if (relativePath == null || relativePath.length() == 0)
            return new URL(rootURL);

         if (relativePath.startsWith("/"))
            relativePath = relativePath.substring(1);

         return new URL(rootURL + relativePath);
      }
   }
}