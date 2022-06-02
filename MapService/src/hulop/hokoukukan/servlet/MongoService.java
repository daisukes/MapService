/*******************************************************************************
 * Copyright (c) 2014, 2022 IBM Corporation, Carnegie Mellon University and
 * others
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/

package hulop.hokoukukan.servlet;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.wink.json4j.JSONArtifact;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;

import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoClientURI;
import com.mongodb.WriteResult;
import com.mongodb.gridfs.GridFS;
import com.mongodb.gridfs.GridFSDBFile;
import com.mongodb.gridfs.GridFSInputFile;
import com.mongodb.util.JSON;

import hulop.hokoukukan.utils.CloudUtils;

public class MongoService {
  public static final String dbName = "floorplans_db";

  private static MongoService sDS = null;

  private MongoClient mClient = null;
  private DB mDB = null;
  private GridFS mFS = null;

  private static MongoClientOptions.Builder optionsBuilder(String cert) throws Exception {
    if (!cert.startsWith("-----BEGIN CERTIFICATE-----")) {
      cert = new String(Base64.getDecoder().decode(cert));
    }

    KeyStore keystore = KeyStore.getInstance("PKCS12");
    keystore.load(null);
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    BufferedInputStream bis = new BufferedInputStream(new ByteArrayInputStream(cert.getBytes()));
    for (int i = 0; bis.available() > 0; i++) {
      try {
        keystore.setCertificateEntry("alias" + i, cf.generateCertificate(bis));
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    bis.close();

    String tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(tmfAlgorithm);
    tmf.init(keystore);
    SSLContext sc = SSLContext.getInstance("TLSv1.2");
    sc.init(null, tmf.getTrustManagers(), new SecureRandom());
    return MongoClientOptions.builder().socketFactory(sc.getSocketFactory()).socketKeepAlive(true);
  }

  public static MongoService getInstance() {
    if (sDS == null) {
      sDS = makeService();
    }
    
    return sDS;
  }

  public static MongoService makeService() {
    try {
      JSONObject credentials = CloudUtils.getCredential(new String[] { "databases-for-mongodb" });
      if (credentials != null) {
        try {
          JSONObject mongodb = credentials.getJSONObject("connection").getJSONObject("mongodb");
          String url = mongodb.getJSONArray("composed").getString(0);
          String cert  = mongodb.getJSONObject("certificate").getString("certificate_base64");
          return new MongoService(url, cert);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
      /*
      String url = CloudUtils.getCredentialURL(new String[] { "cloudantNoSQLDB" }, null);
      if (url != null) {
        System.out.println(url);
        try {
          return new CloudantAdapter(url);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
      */
      String url = CloudUtils.getCredential(new String[] { "compose-for-mongodb" }, "uri", null);
      if (url != null) {
        String cert = CloudUtils.getCredential(new String[] { "compose-for-mongodb" }, "ca_certificate_base64",
                                               null);
        if (cert != null) {
          System.out.println(url);
          System.out.println(cert);
          try {
            return new MongoService(url, cert);
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      }
      url = CloudUtils.getCredentialURL(new String[] { "mongodb", "mongodb-2.4" },
                                        "mongodb://localhost:27017/navi_db");

      System.out.println("creating MongoService: " + url);
      return new MongoService(url, null);
    } catch (Exception e) {
      e.printStackTrace();
    }
    return null;
  }

  public MongoService(String url, String cert) throws Exception {
    MongoClientURI uri = cert != null ? new MongoClientURI(url, optionsBuilder(cert)) : new MongoClientURI(url);
    mClient = new MongoClient(uri);
    mFS = new GridFS(mDB = mClient.getDB(dbName));
  }

  private Set<String> getCollectionNames() {
    return mDB != null ? mDB.getCollectionNames() : null;
  }

  public DBCollection getCollection(String collectionName) {
    return mDB != null && collectionName != null ? mDB.getCollection(collectionName) : null;
  }

  /*
   * GridFS services
   */
  public Object getFileList() {
    return mFS != null ? mFS.getFileList() : null;
  }

  public boolean isFileExists(String id) {
    return getFile(id) != null;
  }

  public GridFSDBFile getFile(String id) {
    return mFS != null ? mFS.findOne(id) : null;
  }

  private GridFSInputFile getNewFile() {
    return mFS != null ? mFS.createFile() : null;
  }

  public String saveFile(InputStream is, String contentType) throws IOException {
    GridFSInputFile file = getNewFile();
    String id = file.getId().toString();
    OutputStream os = getFileOutputStream(id, contentType);
    if (os != null) {
      try {
        byte data[] = new byte[4096];
        int len = 0;
        while ((len = is.read(data, 0, data.length)) > 0) {
          os.write(data, 0, len);
        }
        return id;
      } finally {
        os.close();
      }
    }
    return null;
  }

  public boolean saveFile(String id, InputStream is, String contentType) throws IOException {
    OutputStream os = getFileOutputStream(id, contentType);
    if (os != null) {
      try {
        byte data[] = new byte[4096];
        int len = 0;
        while ((len = is.read(data, 0, data.length)) > 0) {
          os.write(data, 0, len);
        }
        return true;
      } finally {
        os.close();
      }
    }
    return false;
  }

  private OutputStream getFileOutputStream(String id, String contentType) {
    if (mFS != null) {
      try {
        deleteFile(id);
        GridFSInputFile dbFile = mFS.createFile(id);
        if (contentType != null) {
          dbFile.setContentType(contentType);
        }
        return dbFile.getOutputStream();
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return null;
  }

  public boolean deleteFile(String id) {
    if (isFileExists(id)) {
      mFS.remove(id);
      return true;
    }
    return false;
  }

  private boolean readFile(GridFSDBFile dbFile, OutputStream os) throws IOException {
    InputStream is = null;
    try {
      is = dbFile.getInputStream();
      byte data[] = new byte[4096];
      int len = 0;
      while ((len = is.read(data, 0, data.length)) > 0) {
        os.write(data, 0, len);
      }
      return true;
    } finally {
      if (is != null) {
        is.close();
      }
    }
  }

  /*
   * http services
   */
  public void sendFile(String path, HttpServletResponse response) throws IOException {
    GridFSDBFile file = getFile(path);
    if (file == null) {
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, String.format("File %s does not exist", path));
    } else {
      String contentType = file.getContentType();
      if (contentType != null) {
        response.setContentType(contentType);
      }
      OutputStream os = null;
      try {
        readFile(file, os = response.getOutputStream());
      } catch (Exception e) {
        System.err.println("Send error: " + path);
      } finally {
        if (os != null) {
          os.close();
        }
      }
    }
  }

  public void sendJSON(Object obj, HttpServletRequest request, HttpServletResponse response) throws IOException {
    boolean gzip = false;
    if (request != null) {
      String acceptedEncodings = request.getHeader("accept-encoding");
      gzip = acceptedEncodings != null && acceptedEncodings.indexOf("gzip") != -1;
    }
    if (obj instanceof WriteResult || "OK".equals(obj)) {
      try {
        obj = new JSONObject().put("result", "OK");
      } catch (JSONException e) {
        e.printStackTrace();
      }
      gzip = false;
    }
    OutputStream os = null;
    try {
      if (obj instanceof String) {
        obj = ((String)obj).getBytes("UTF-8");
      } else if (!(obj instanceof JSONArtifact)) {
        obj = JSON.serialize(obj).getBytes("UTF-8");
      }
      if ((obj instanceof byte[]) && ((byte[]) obj).length < 860) {
        gzip = false;
      }
      response.setCharacterEncoding("UTF-8");
      response.setContentType("application/json");
      os = response.getOutputStream();
      if (gzip) {
        response.setHeader("Content-Encoding", "gzip");
        GZIPOutputStream gzos = new GZIPOutputStream(os);
        writeJSON(obj, gzos);
        gzos.finish();
        gzos.close();
      } else {
        writeJSON(obj, os);
      }
    } catch (Exception e) {
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.toString());
      e.printStackTrace();
    } finally {
      if (os != null) {
        os.close();
      }
    }
  }

  private static void writeJSON(Object obj, OutputStream os) throws JSONException, IOException {
    if (obj instanceof JSONArtifact) {
      // see https://issues.apache.org/jira/browse/WINK-413
      // ((JSONArtifact) obj).write(os);
      Writer writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
      ((JSONArtifact) obj).write(writer);
      writer.flush();
    } else {
      os.write((byte[]) obj);
    }
  }

}
