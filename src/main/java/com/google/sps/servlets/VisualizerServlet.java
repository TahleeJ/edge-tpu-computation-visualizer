package com.google.sps.servlets;

import com.google.appengine.api.datastore.DatastoreService;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.appengine.api.datastore.Key;
import com.google.appengine.api.datastore.KeyFactory.Builder;
import com.google.appengine.api.datastore.PreparedQuery;
import com.google.appengine.api.datastore.Query;
import com.google.appengine.api.datastore.Query.Filter;
import com.google.appengine.api.datastore.Query.FilterOperator;
import com.google.appengine.api.datastore.Query.FilterPredicate;
import com.google.appengine.api.datastore.Query.SortDirection;
import com.google.appengine.tools.cloudstorage.GcsFileOptions;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.common.io.ByteStreams;
import com.google.gson.Gson;
import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;
import com.google.protobuf.TextFormat.ParseException.*;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.sps.data.*;
import com.google.sps.proto.MemaccessCheckerDataProto.*;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import javax.servlet.ServletException;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

@WebServlet("/visualizer")
@MultipartConfig()
public class VisualizerServlet extends HttpServlet {
  // Variables to hold the information about the last uploaded file, time zone, and current user.
  private static String timeZone = ZoneOffset.UTC.getId();
  private static String user = "All";
  private static FileJson fileJson = new FileJson();
  private static Entity fileEntity = new Entity("File");
  private static String errorMessage = "";

  @Override 
  public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    if (request.getParameter("time").equals("false")) {
      // Does NOT update the time zone.

      if (request.getParameter("user").equals("false")) {
        // Does NOT update the current user.

        Query queryFile = new Query("File").addSort("time", SortDirection.DESCENDING);
        Query queryUser = new Query("User").addSort("time", SortDirection.DESCENDING);

        DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

        PreparedQuery userResults = datastore.prepare(queryUser);
 
        // Gets the current time zone string.
        ZonedDateTime dateTime = ZonedDateTime.now(ZoneId.of(timeZone));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("z");

        // Appends the correct time zone to the date and time string and retrieves the JSON string 
        // containing the file upload information and the total collection of files.
        ReturnJson returnJson = 
            new ReturnJson(
                getFileJson(timeZone, fileEntity), 
                getFiles(), 
                getUsers(), 
                user, 
                timeZone, 
                dateTime.format(formatter),
                errorMessage);
        
        errorMessage = "";

        Gson gson = new Gson();

        response.setContentType("application/json;");
        response.getWriter().println(gson.toJson(returnJson));
      } else {
        // Updates the current user.
        String name = request.getParameter("user-name");
        user = name;  

        // Adds the new user into datastore.
        if (request.getParameter("new").equals("true")) {
          DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

          // Checks if the user already exists.

          Filter propertyFilter = new FilterPredicate("user-name", FilterOperator.EQUAL, user);
          Query userCheck = new Query("User").setFilter(propertyFilter);

          if (((PreparedQuery) datastore.prepare(userCheck)).countEntities() == 0) {
            // Puts the entered user into datastore.
            Entity userEntity = new Entity("User");
            userEntity.setProperty("user-name", user);
            userEntity.setProperty("time", new Date());
            datastore.put(userEntity);
          } else {
            errorMessage = "User already exists.";
          }
        }     
      } 
    } else {
      // Updates the selected time zone.
      String zone = request.getParameter("zone");

      timeZone = zone; 
    }
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response) 
      throws IOException, ServletException{
    String upload = request.getParameter("upload");

    if (upload.equals("true")) {
      // Retrieve the uploaded file.
      Part filePart = request.getPart("file-input");
          
      // Will not execute if the user failed to select a file after clicking "upload".
      if (filePart.getSubmittedFileName().length() > 0) {
        InputStream fileInputStream = filePart.getInputStream();
        String fileName = filePart.getSubmittedFileName();

        MemaccessCheckerData memaccessChecker = getMessage(fileInputStream, fileName);

        // Checks if the user uploaded a compatible file that can be parsed
        if (memaccessChecker != null) {
          // Put the file information into datastore.
          ZonedDateTime dateTime = ZonedDateTime.now(ZoneId.of(ZoneOffset.UTC.getId()));
          DateTimeFormatter formatter = DateTimeFormatter.ISO_ZONED_DATE_TIME;

          String checkerName = 
              (memaccessChecker.getName().equals("")) 
                  ? filePart.getSubmittedFileName() 
                  : memaccessChecker.getName();

          Entity memaccessCheckerUpload = new Entity("File");
          memaccessCheckerUpload.setProperty("date", dateTime.format(formatter));
          memaccessCheckerUpload.setProperty("time", new Date());
          // ^ Purely for sorting purposes only

          memaccessCheckerUpload.setProperty("name", checkerName);
          memaccessCheckerUpload.setProperty("user", user);
          memaccessCheckerUpload.setProperty(
              "memaccess-checker", dateTime.format(formatter) + ":" + checkerName);        

          DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
          
          datastore.put(memaccessCheckerUpload);

          // Write file to Cloud Storage using the file's upload time and name as its unique name.
          GcsFileOptions instance = GcsFileOptions.getDefaultInstance();
          GcsService gcsService = GcsServiceFactory.createGcsService();
          GcsFilename gcsFile = 
              new GcsFilename("trace_info_files", dateTime.format(formatter) + ":" + checkerName);

          // Gets the file in its byte array form.
          byte[] byteArray = memaccessChecker.toByteArray();
          ByteBuffer buffer = ByteBuffer.wrap(byteArray, 0, byteArray.length);

          // Create and write to the GCS object.
          gcsService.createOrReplace(gcsFile, instance, buffer);

          // Updates the last submitted file.
          fileEntity = memaccessCheckerUpload;

          // Holds the last uploaded file information.
          String fileSize = getBytes(filePart.getSize());
          String fileTrace = 
              memaccessChecker.getName().equals("") ? "No name provided" : memaccessChecker.getName();
              
          int fileTiles = memaccessChecker.getNumTiles();
          String narrowBytes = commaFormat(memaccessChecker.getNarrowMemorySizeBytes());
          String wideBytes = commaFormat(memaccessChecker.getWideMemorySizeBytes());

          fileJson =
              new FileJson(fileName, fileSize, fileTrace, fileTiles, narrowBytes, wideBytes, user);
        }
      } else {
        // Resets the last uploaded file to "null" to help provide feedback to the user.

        fileJson = new FileJson();
        fileEntity = new Entity("File");
      }
    } else {
      // Purges datastore as specified.
      if (request.getParameter("purge").equals("true")) {

        // Purge all users.
        if (request.getParameter("users").equals("true")) {
          purgeAll(true);

          user = "All";
        }

        // Purge all files.
        if (request.getParameter("files").equals("true")) {
          purgeAll(false);

          fileJson = new FileJson();
          fileEntity = new Entity("File");
        }
      } else {
        // Deletes a single user.
        if (request.getParameter("user").equals("true")) {
          try {
            purgeEntity(
              true,
              Long.parseLong(request.getParameter("user-id")), 
              request.getParameter("user-name"));
          } catch(EntityNotFoundException e) {
            System.out.println("User not found.");
          }

          user = "All";
        } else {
          // Deletes a single file.

          Long id = Long.parseLong(request.getParameter("file-id"));
          
          DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
          Query queryFile = new Query("File").addSort("time", SortDirection.DESCENDING);

          Entity lastUploadedFile = 
              ((PreparedQuery) datastore.prepare(queryFile)).asIterator().next();

          Long lastId = lastUploadedFile.getKey().getId();

          // Resets last uploaded file if the deleted file is the most recent file upload.
          if (lastId.equals(id)) {
            fileJson = new FileJson();
            fileEntity = new Entity("File");
          }

          try {
            purgeEntity(false, id, null);
          } catch(EntityNotFoundException e) {
            System.out.println("File not found.");
          }       
        }
      } 
    }  

    response.sendRedirect("/index.html");
  }

  /** Creates a proto message out of the uploaded file's input stream.
   * 
   * @param fileInputStream is the input stream of the uploaded file
   * @param fileName is the name of the uploaded file
   * @throws IOException
   * @throws InvalidProtocolBufferException if the byte array of the uploaded binary file cannnot
   *                                        be parsed into a MemaccessChecker message
   * @throws UnsupportedEncodingException if the uploaded text file cannot be parsed into a 
   *                                      MemaccessChecker message
   */
  private static MemaccessCheckerData getMessage(InputStream fileInputStream, String fileName) 
      throws IOException, InvalidProtocolBufferException, UnsupportedEncodingException {
    MemaccessCheckerData memaccessChecker = null;

    // Checks if the file uploaded is a binary file or a text file.
    if (fileName.toLowerCase().endsWith(".bin")) {
      // Parses the file as a binary file if it is compatible.

      byte[] byteArray = ByteStreams.toByteArray(fileInputStream);

      try {
        memaccessChecker = MemaccessCheckerData.parseFrom(byteArray);
      } catch (InvalidProtocolBufferException e) {
        errorMessage = e.getMessage();
      }
    } else {
      // Parses the file as a text file if it is compatible.

      InputStreamReader reader = new InputStreamReader(fileInputStream, "ASCII");
      MemaccessCheckerData.Builder builder = MemaccessCheckerData.newBuilder();
      
      try {        
        TextFormat.merge(reader, builder);

        memaccessChecker = builder.build();
      } catch (ParseException e) {
        errorMessage = e.getMessage();
      }
    }

    return memaccessChecker;
  }

  /** Function to retrieve the file size information in terms of Bytes/KB/MB/GB.
   *
   * @param size is the size of the uploaded file
   * @return the String representation of the number of file size
   */
  private static String getBytes(long size) {
    double bytes = (double) size;
    String result = "";

    if (bytes < Math.pow(1024, 1)) {
      result += String.format("%.2f", bytes) + " Bytes";
    } else if (bytes < Math.pow(1024, 2)) {
      bytes = (double) bytes / Math.pow(1024, 1);
      result += String.format("%.2f", bytes) + " KB";
    } else if (bytes < Math.pow(1024, 3)) {
      bytes = (double) bytes / Math.pow(1024, 2);
      result += String.format("%.2f", bytes) + " MB";
    } else {
      bytes = (double) bytes / Math.pow(1024, 3);
      result += String.format("%.2f", bytes) + " GB";
    }

    return result;
  }

  /** Adds a comma for every 3 digits.
   * 
   * @param input is the size of the narrow/wide memory passed in
   * @return the String representation of the memory size
   */
  private static String commaFormat(int input) {
    Integer value = new Integer(input);
    String result = value.toString();
    
    char[] resultArray = result.toCharArray();
    List<String> chars = new ArrayList<String>();

    int count = 0;
    
    for (int i = resultArray.length - 1; i > -1; i--) {
      count++;

      chars.add(0, String.valueOf(resultArray[i]));

      // Appends a comma to the front of the string if there are more digits to come.
      if (count == 3 && i != 0) {
        chars.add(0, ",");
        count = 0;
      }
    }

    String finalResult = "";

    for (String character : chars) {
      finalResult += character;
    }

    return finalResult;
  }

  /** Determines the appropriate file information to be displayed on the page.
   * 
   * @param zone is the current selected time zone
   * @param fileEntity is the last uploaded file by this user in that was put into Datastore
   * @return the FileJson object containing the most recent uploaded file.
   */
  private static FileJson getFileJson(String zone, Entity fileEntity) {
    String dateTimeString = (String) fileEntity.getProperty("date");

    if (dateTimeString == null) {
      // Sends the time zone information only. Will be used when there is no last uploaded file.

      ZonedDateTime dateTime = ZonedDateTime.now(ZoneId.of(zone));
      DateTimeFormatter formatter = DateTimeFormatter.ofPattern("z");

      fileJson = new FileJson(dateTime.format(formatter), zone);
    } else {
      // Sends both file and time information.

      fileJson = new FileJson(fileJson, dateTimeString, zone);
    }

    return fileJson;
  }

  /** Retrieves the information of all of the uploaded files based on the selected filter.
   * 
   * @return the list of filtered files
   */
  private static List<LoadFile> getFiles() {
    boolean userFilesExist = true;

    Query queryFile;

    DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

    // Filters files to display based on current user.
    if (user.equals("All")) {
      queryFile = new Query("File").addSort("time", SortDirection.DESCENDING);
    } else {
      // Filters files according to current user.

      Filter propertyFilter = new FilterPredicate("user", FilterOperator.EQUAL, user);

      queryFile = 
          new Query("File")
              .setFilter(propertyFilter)
              .addSort("time", SortDirection.DESCENDING);

      if (((PreparedQuery) datastore.prepare(queryFile)).countEntities() == 0) {
        // Uses default "All" users option if current user has not uploaded files under their
        // name.

        queryFile = new Query("File").addSort("time", SortDirection.DESCENDING);
        userFilesExist = false;
      }
    }        

    PreparedQuery fileResults = datastore.prepare(queryFile);

    ArrayList<LoadFile> files = new ArrayList<>();
    String dateTimeString;

    // Creates a collection of LoadFile objects with the proper information about their storage.
    for (Entity fileEntity : fileResults.asIterable()) {
      dateTimeString = fileEntity.getProperty("date").toString();

      files.add(
          new LoadFile(
              fileEntity.getKey().getId(),
              (String) fileEntity.getProperty("name"),
              dateTimeString,
              timeZone,
              user,
              userFilesExist));
    }

    return files;
  }

  /** Assembles a collection of all known users.
   * 
   * @return the total collection of users in Datastore
   */
  private static List<User> getUsers() {
    Query queryUser = new Query("User").addSort("time", SortDirection.DESCENDING);

    DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
    PreparedQuery userResults = datastore.prepare(queryUser);
          
    ArrayList<User> users = new ArrayList<>();

    for (Entity entity : userResults.asIterable()) {
      users.add(new User(entity.getKey().getId(), (String) entity.getProperty("user-name")));
    }

    return users;
  }

  /** Clears datastore and/or Cloud Storage of users and/or files as specified.
   *
   * @param allUsers dictates whether to delete all users or all files
   * @throws IOException
   */
  private static void purgeAll(boolean allUsers) throws IOException {
    DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();

    if (!allUsers) {
      // Deletes all files.

      Query queryFile = new Query("File");

      GcsService gcsService = GcsServiceFactory.createGcsService();
      GcsFilename fileName;

      for (Entity entity : ((PreparedQuery) datastore.prepare(queryFile)).asIterable()) {
        fileName = new GcsFilename("trace_info_files", entity.getProperty("memaccess-checker").toString());

        gcsService.delete(fileName);
        datastore.delete(entity.getKey());
      }
    } else {
      // Deletes all users.

      Query queryUser = new Query("User");

      for (Entity entity : ((PreparedQuery) datastore.prepare(queryUser)).asIterable()) {
        datastore.delete(entity.getKey());
      }

      // Resets each file's user that previously had the deleted user to the default "All".
      Query queryFile = new Query("File");
      PreparedQuery fileResults = datastore.prepare(queryFile);
      
      for (Entity entity : fileResults.asIterable()) {
        entity.setProperty("user", "All");
      }
    }
  }

  /** Deletes a single user or file from datastore and/or Cloud Storage.
   * 
   * @param isUser dictates whether the entity being deleted is a user or a file
   * @param id is the id of the entity in Datastore
   * @param name is the name of the user if it is a user being deleted, null if a file.
   * @throws IOException
   * @throws EntityNotFoundException if the passed in id and its corresponding key is not
   *                                 present in Datastore
   */
  private static void purgeEntity(boolean isUser, Long id, String name) 
      throws IOException, EntityNotFoundException {
    DatastoreService datastore = DatastoreServiceFactory.getDatastoreService();
    Key key = null;

    if (isUser) {
      // Retrieves the user based on its key.
      key = new Builder("User", id).getKey();  

      Filter propertyFilter = new FilterPredicate("user", FilterOperator.EQUAL, name);
      Query queryFile = new Query("File").setFilter(propertyFilter);
      PreparedQuery fileResults = datastore.prepare(queryFile);

      // Resets each file's user that previously had the deleted user to the default "All".
      for (Entity entity : fileResults.asIterable()) {
        entity.setProperty("user", "All");
      }
    } else {
      key = new Builder("File", id).getKey();
      
      // Deletes file from Cloud Storage.
      Entity fileEntity = datastore.get(key);

      GcsService gcsService = GcsServiceFactory.createGcsService();
      GcsFilename fileName = 
            new GcsFilename("trace_info_files", fileEntity.getProperty("memaccess-checker").toString());
          
      gcsService.delete(fileName);
    }

    datastore.delete(key); 
  }
}