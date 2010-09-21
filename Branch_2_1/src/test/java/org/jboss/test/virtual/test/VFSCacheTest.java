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
package org.jboss.test.virtual.test;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.Map;

import org.jboss.util.Strings;
import org.jboss.virtual.VFS;
import org.jboss.virtual.VirtualFile;
import org.jboss.virtual.plugins.context.jar.JarContextFactory;
import org.jboss.virtual.spi.VFSContext;
import org.jboss.virtual.spi.VFSContextFactory;
import org.jboss.virtual.spi.cache.CacheStatistics;
import org.jboss.virtual.spi.cache.VFSCache;
import org.jboss.virtual.spi.cache.VFSCacheFactory;
import org.jboss.virtual.spi.cache.helpers.NoopVFSCache;

/**
 * VFSCache Test.
 *
 * @author <a href="ales.justin@jboss.com">Ales Justin</a>
 */
public abstract class VFSCacheTest extends AbstractVFSRegistryTest
{
   public VFSCacheTest(String name)
   {
      super(name);
   }

   protected abstract VFSCache createCache();

   protected void configureCache(VFSCache cache) throws Exception
   {
   }

   protected void stopCache(VFSCache cache)
   {
      if (cache != null)
         cache.stop();
   }

   @SuppressWarnings("deprecation")
   public void testCache() throws Exception
   {
      URL url = getResource("/vfs/test/nested");

      VFSCache cache = createCache();
      cache.start();
      try
      {
         VFSCacheFactory.setInstance(cache);
         try
         {
            configureCache(cache);

            VirtualFile root = VFS.getRoot(url);

            VirtualFile file = root.findChild("/nested.jar/META-INF/empty.txt");
            URL fileURL = file.toURL();
            VirtualFile nested = root.findChild("/nested.jar/complex.jar/subfolder/subsubfolder/subsubchild");
            URL nestedURL = nested.toURL();

            assertEquals(file, VFS.getRoot(fileURL));
            assertEquals(nested, VFS.getRoot(nestedURL));

            VFSCacheFactory.setInstance(null);
            VFSCache wrapper = new WrapperVFSCache(cache);
            VFSCacheFactory.setInstance(wrapper);

            assertEquals(file, VFS.getRoot(fileURL));
            assertEquals(nested, VFS.getRoot(nestedURL));
         }
         finally
         {
            VFSCacheFactory.setInstance(null);
         }
      }
      finally
      {
         stopCache(cache);
      }
   }

   protected abstract void testCachedContexts(Iterable<VFSContext> iter);

   public void testCacheStatistics() throws Exception
   {
      URL url = getResource("/vfs/test/nested");

      VFSCache cache = createCache();
      cache.start();
      try
      {
         if (cache instanceof CacheStatistics)
         {
            CacheStatistics statistics = CacheStatistics.class.cast(cache);
            VFSCacheFactory.setInstance(cache);
            try
            {
               configureCache(cache);

               VirtualFile root = VFS.getRoot(url);
               assertNotNull(root);

               Iterable<VFSContext> iter = statistics.getCachedContexts();
               testCachedContexts(iter);

               assertEquals(1, statistics.size());
               assertTrue(statistics.lastInsert() != 0);
            }
            finally
            {
               VFSCacheFactory.setInstance(null);
            }
         }
      }
      finally
      {
         stopCache(cache);
      }
   }

   protected Class<? extends VFSCache> getCacheClass()
   {
      VFSCache cache = createCache();
      return cache.getClass();
   }

   protected Iterable<String> populateRequiredSystemProperties()
   {
      return Collections.emptySet();
   }

   protected abstract Map<Object, Object> getMap();

   public void testCacheFactory() throws Exception
   {
      VFSCache cache = null;
      String cacheClassName = getCacheClass().getName();

      VFSCacheFactory.setInstance(null);
      try
      {
         Iterable<String> keys = populateRequiredSystemProperties();
         try
         {
            cache = VFSCacheFactory.getInstance(cacheClassName);
            assertNotNull(cache);
            assertTrue(cache instanceof NoopVFSCache == false);
            cache.flush();
         }
         finally
         {
            for (String key : keys)
               System.clearProperty(key);
         }

         VFSCacheFactory.setInstance(null);

         VFSCache mapParamCache = VFSCacheFactory.getInstance(cacheClassName, getMap());
         // need new instance, so we know we're really testing map parameter
         assertNotSame(cache, mapParamCache);
         cache = mapParamCache;
         assertNotNull(cache);
         assertTrue(cache instanceof NoopVFSCache == false);
         cache.flush();
      }
      finally
      {
         stopCache(cache);
         VFSCacheFactory.setInstance(null);
      }
   }

   /**
    * Test jar path.
    *
    * @throws Exception for any error
    */
   public void testJarPath() throws Exception
   {
      // to circumvent another bug in VFS
      Map<String, VFSContextFactory> factoryByProtocol = getFactoryByProtocol();
      VFSContextFactory oldFactory = factoryByProtocol.put("jar", new JarContextFactory());
      
      VFSCache cache = createCache();
      cache.start();
      try
      {
         VFSCacheFactory.setInstance(cache);
         try
         {
            configureCache(cache);

            URL url = getResource("/vfs/test/jar1.jar");
            URL manifestURL = new URL("jar:" + url.toExternalForm() + "!/META-INF/MANIFEST.MF");
            
            // first we ask for a jar:file: resource
            
            VirtualFile manifest = VFS.getRoot(manifestURL);

            assertNotNull(manifest);
            
            // then we ask for a file: resource
            
            VirtualFile jar = VFS.getRoot(url);
            
            assertNotNull(jar);
         }
         catch(IOException e)
         {
            fail("failed to get the proper files: " + e.getMessage());
         }
         finally
         {
            VFSCacheFactory.setInstance(null);
         }
      }
      finally
      {
         factoryByProtocol.put("jar", oldFactory);
         
         stopCache(cache);
      }
   }

   public void testCanonicalPath() throws Exception
   {
      ProtectionDomain pd = getClass().getProtectionDomain();
      URL url = pd.getCodeSource().getLocation();
      String urlString = url.toExternalForm();
      if (urlString.endsWith("/") == false)
         urlString += "/";
      String testDir = urlString + "vfs/../vfs/test/";
      URL testURL = Strings.toURL(testDir);

      VFSCache cache = createCache();
      cache.start();
      try
      {
         VFSCacheFactory.setInstance(cache);
         try
         {
            configureCache(cache);

            VirtualFile testVF = VFS.getRoot(testURL);
            URL jar1URL = new URL(testDir + "jar1.jar/");
            VirtualFile jar1VF = VFS.getRoot(jar1URL);
            assertEquals(testVF, jar1VF.getParent()); // should find previous root
         }
         finally
         {
            VFSCacheFactory.setInstance(null);
         }
      }
      finally
      {
         stopCache(cache);
      }
   }

   private class WrapperVFSCache implements VFSCache
   {
      private VFSCache delegate;

      private WrapperVFSCache(VFSCache delegate)
      {
         this.delegate = delegate;
      }

      public VFSContext findContext(URI uri)
      {
         return delegate.findContext(uri);
      }

      public VFSContext findContext(URL url)
      {
         return delegate.findContext(url);
      }

      public void putContext(VFSContext context)
      {
         throw new IllegalArgumentException("Context should already be there: " + context);
      }

      public void removeContext(VFSContext context)
      {
      }

      public void start() throws Exception
      {
      }

      public void stop()
      {
      }

      public void flush()
      {
      }
   }
}