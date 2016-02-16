package net.wasdev.gameon.room;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

/**
 * A very simple room.
 * 
 * The intent of this file is to keep an entire room implementation within one Java file, 
 * and to try to minimise its reliance on outside technologies, beyond those required by 
 * gameon (WebSockets, Json)
 * 
 * Although it would be trivial to refactor out into multiple classes, doing so can make it
 * harder to see 'everything' needed for a room in one go. 
 */
@ServerEndpoint("/simpleRoom")
@WebListener
public class VerySimpleRoom implements ServletContextListener {

    private final static String USERNAME = "username";
    private final static String USERID = "userId";
    private final static String BOOKMARK = "bookmark";
    private final static String CONTENT = "content";
    private final static String LOCATION = "location";
    private final static String TYPE = "type";
    private final static String NAME = "name";
    private final static String FULLNAME = "fullName";
    private final static String DESCRIPTION = "description";

    private Set<String> playersInRoom = Collections.synchronizedSet(new HashSet<String>());

    private static final String name = "SimpleRoom";
    private static final String fullName = "A Very Simple Room.";
    private static final String description = "You are in the worlds most simple room, there is nothing to do here.";

    private static long bookmark = 0;

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Room registration
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * The gameon-signature method requires a hmac hash, this method calculates it.
     * @param stuffToHash List of string values to apply to the hmac
     * @param key The key to init the hmac with
     * @return The hmac as a base64 encoded string.
     * @throws NoSuchAlgorithmException if HmacSHA256 is not found
     * @throws InvalidKeyException Should not be thrown unless there are internal hmac issues.
     * @throws UnsupportedEncodingException If the keystring or hash string are not UTF-8
     */
    private String buildHmac(List<String> stuffToHash, String key) throws NoSuchAlgorithmException, InvalidKeyException, UnsupportedEncodingException{
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
        
        StringBuffer hashData = new StringBuffer();
        for(String s: stuffToHash){
            hashData.append(s);            
        }
        
        return Base64.getEncoder().encodeToString( mac.doFinal(hashData.toString().getBytes("UTF-8")) );
    }
    
    /**
     * The gameon-sig-body header requires the sha256 hash of the body content. This method calculates it.
     * @param data The string to hash
     * @return the sha256 hash as a base64 encoded string
     * @throws NoSuchAlgorithmException If SHA-256 is not found
     * @throws UnsupportedEncodingException If the String is not UTF-8
     */
    private String buildHash(String data) throws NoSuchAlgorithmException, UnsupportedEncodingException{
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(data.getBytes("UTF-8")); 
        byte[] digest = md.digest();
        return Base64.getEncoder().encodeToString( digest );
    }
    
    /**
     * A Trust Manager that trusts everyone.
     */
    public class TheVeryTrustingTrustManager implements X509TrustManager {
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return null;
        }
        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {   }
        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {   }
    }
    /**
     * A Hostname verifier that agrees everything is verified. 
     */
    public class TheNotVerySensibleHostnameVerifier implements HostnameVerifier {
        public boolean verify(String string, SSLSession sslSession) {
            return true;
        }
    }
    
    /**
     * Entry point at application start, we use this to test for & perform room registration.
     */
    @Override
    public final void contextInitialized(final ServletContextEvent e) {
              
        // for running against the real remote gameon.
        //String registrationUrl = "https://game-on.org/map/v1/sites";
        //String endPointUrl = "ws://<ip and port of host that gameon can reach>/rooms/simpleRoom

        // for when running in a docker container with game-on all running
        // locally.
        String registrationUrl = "http://map:9080/map/v1/sites";
        String endPointUrl = "ws://simpleroom:9080/rooms/simpleRoom";

        // credentials, obtained from the gameon instance to connect to.
        String userId = "dummy.DevUser";
        String key = "sfP8wMcjTPyt8I71Gl6o0j+wnMdwxEQ3r0VaybsSn0c=";

        // check if we are already registered..
        try {
            // build the query request.
            String queryParams = "name=" + name + "&owner=" + userId;
            
            TrustManager[] trustManager = new TrustManager[] {new TheVeryTrustingTrustManager()};

            // We don't want to worry about importing the game-on cert into 
            // the jvm trust store.. so instead, we'll create an ssl config
            // that no longer cares. 
            // This is handy for testing, but for production you'd probably 
            // want to goto the effort of setting up a truststore correctly.
            SSLContext sslContext = null;
            try {
                sslContext = SSLContext.getInstance("SSL");
                sslContext.init(null, trustManager, new java.security.SecureRandom());
            } catch (NoSuchAlgorithmException ex) {
                System.out.println("Error, unable to get algo SSL");
            }catch (KeyManagementException ex) {
                System.out.println("Key management exception!! ");
            }
            
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());

            // build the complete query url..
            System.out.println("Querying room registration using url " + registrationUrl);
            URL u = new URL(registrationUrl + "?" + queryParams );
            HttpsURLConnection con = (HttpsURLConnection) u.openConnection();
            con.setHostnameVerifier(new TheNotVerySensibleHostnameVerifier());
            con.setDoOutput(true);
            con.setDoInput(true);
            con.setRequestProperty("Content-Type", "application/json;");
            con.setRequestProperty("Accept", "application/json,text/plain");
            con.setRequestProperty("Method", "GET");

            //initiate the request.
            int httpResult = con.getResponseCode();
            if (httpResult == HttpURLConnection.HTTP_OK ) {
                //if the result was 200, then we found a room with this id & owner..
                //which is either a previous registration by us, or another room with 
                //the same owner & roomname
                //We won't register our room in this case, although we _could_ choose
                //do do an update instead.. (we'd need to parse the json response, and 
                //collect the room id, then do a PUT request with our new data.. )
                System.out.println("We are already registered, there is no need to register this room");
            } else {
                System.out.println("Beginning registration.");

                // build the registration payload (post data)
                JsonObjectBuilder registrationPayload = Json.createObjectBuilder();
                // add the basic room info.
                registrationPayload.add("name", name);
                registrationPayload.add("fullName", fullName);
                registrationPayload.add("description", description);
                // add the doorway descriptions we'd like the game to use if it
                // wires us to other rooms.
                JsonObjectBuilder doors = Json.createObjectBuilder();
                doors.add("n", "A Large doorway to the north");
                doors.add("s", "A winding path leading off to the south");
                doors.add("e", "An overgrown road, covered in brambles");
                doors.add("w", "A shiny metal door, with a bright red handle");
                doors.add("u", "A spiral set of stairs, leading upward into the ceiling");
                doors.add("d", "A tunnel, leading down into the earth");
                registrationPayload.add("doors", doors.build());
                // add the connection info for the room to connect back to us..
                JsonObjectBuilder connInfo = Json.createObjectBuilder();
                connInfo.add("type", "websocket"); // the only current supported
                                                   // type.
                connInfo.add("target", endPointUrl);
                registrationPayload.add("connectionDetails", connInfo.build());
                                               
                String registrationPayloadString = registrationPayload.build().toString();
                
                Instant now = Instant.now();
                String dateValue = now.toString();

                String bodyHash = buildHash(registrationPayloadString);
                
                System.out.println("Building hmac with "+userId+dateValue+bodyHash);
                String hmac = buildHmac(Arrays.asList(new String[] {
                                           userId,
                                           dateValue,
                                           bodyHash
                                       }),key);
                

                // build the complete registration url..
                System.out.println("Beginning registration using url " + registrationUrl);
                u = new URL(registrationUrl);
                con = (HttpsURLConnection) u.openConnection();
                con.setHostnameVerifier(new TheNotVerySensibleHostnameVerifier());
                con.setDoOutput(true);
                con.setDoInput(true);
                con.setRequestProperty("Content-Type", "application/json;");
                con.setRequestProperty("Accept", "application/json,text/plain");
                con.setRequestProperty("Method", "POST");
                con.setRequestProperty("gameon-id", userId);
                con.setRequestProperty("gameon-date", dateValue);
                con.setRequestProperty("gameon-sig-body", bodyHash);
                con.setRequestProperty("gameon-signature", hmac);
                OutputStream os = con.getOutputStream();
                
                os.write(registrationPayloadString.getBytes("UTF-8"));
                os.close();

                System.out.println("RegistrationPayload :\n "+registrationPayloadString);

                httpResult = con.getResponseCode();
                if (httpResult == HttpURLConnection.HTTP_OK || httpResult == HttpURLConnection.HTTP_CREATED) {
                    try (BufferedReader buffer = new BufferedReader(
                            new InputStreamReader(con.getInputStream(), "UTF-8"))) {
                        String response = buffer.lines().collect(Collectors.joining("\n"));
                        System.out.println("Registration reports success.");
                        System.out.println(response);
                        // here we should remember the exits we're told about,
                        // so we can
                        // use them when the user does /go direction
                        // But we're not dealing with exits here (yet)..
                        // user's will have to /sos out of us .. (bad, but ok
                        // for now)
                    }
                } else {
                    System.out.println(
                            "Registration gave http code: " + con.getResponseCode() + " " + con.getResponseMessage());
                    // registration sends payload with info why registration
                    // failed.
                    try (BufferedReader buffer = new BufferedReader(
                            new InputStreamReader(con.getErrorStream(), "UTF-8"))) {
                        String response = buffer.lines().collect(Collectors.joining("\n"));
                        System.out.println(response);
                    }
                    System.out.println("Room Registration FAILED .. this room has NOT been registered");
                }

            }
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // Here we could deregister, if we wanted.. we'd need to read the registration/query
        // response to cache the room id, so we could remove it as we shut down.
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Websocket methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @OnOpen
    public void onOpen(Session session, EndpointConfig ec) {
        System.out.println("A new connection has been made to the room.");
        //send ack 
        try{
            JsonObjectBuilder ack = Json.createObjectBuilder();
            JsonArrayBuilder versions = Json.createArrayBuilder();
            versions.add(1);
            ack.add("version", versions.build());
            String msg = "ack," + ack.build().toString();
            session.getBasicRemote().sendText(msg);
        }catch(IOException io){
            System.out.println("Error sending initial room ack");
            io.printStackTrace();
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason r) {
        System.out.println("A connection to the room has been closed");
    }

    @OnError
    public void onError(Throwable t) {
        System.out.println("Websocket connection has broken");
        t.printStackTrace();
    }

    @OnMessage
    public void receiveMessage(String message, Session session) throws IOException {
        String[] contents = splitRouting(message);
        if (contents[0].equals("roomHello")) {
            addNewPlayer(session, contents[2]);
            return;
        }
        if (contents[0].equals("room")) {
            processCommand(session, contents[2]);
            return;
        }
        if (contents[0].equals("roomGoodbye")) {
            removePlayer(session, contents[2]);
            return;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Room methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // add a new player to the room
    private void addNewPlayer(Session session, String json) throws IOException {
        if (session.getUserProperties().get(USERNAME) != null) {
            return; // already seen this user before on this socket
        }
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String username = getValue(msg.get(USERNAME));
        String userid = getValue(msg.get(USERID));

        if (playersInRoom.add(userid)) {
            // broadcast that the user has entered the room
            sendMessageToRoom(session, "Player " + username + " has entered the room", "You have entered the room",
                    userid);

            // now send the room info
            // this is the required response to a roomHello event, which is the
            // only reason we are in this method.
            JsonObjectBuilder response = Json.createObjectBuilder();
            response.add(TYPE, LOCATION);
            response.add(NAME, name);
            response.add(FULLNAME, fullName);
            response.add(DESCRIPTION, description);
            session.getBasicRemote().sendText("player," + userid + "," + response.build().toString());
        }
    }

    // remove a player from the room.
    private void removePlayer(Session session, String json) throws IOException {
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String username = getValue(msg.get(USERNAME));
        String userid = getValue(msg.get(USERID));
        playersInRoom.remove(userid);

        // broadcast that the user has left the room
        sendMessageToRoom(session, "Player " + username + " has left the room", null, userid);
    }

    // process a command
    private void processCommand(Session session, String json) throws IOException {
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String userid = getValue(msg.get(USERID));
        String username = getValue(msg.get(USERNAME));
        String content = getValue(msg.get(CONTENT)).toString().toLowerCase();
        System.out.println("Command received from the user, " + content);

        // handle look command
        if (content.equals("/look")) {
            // resend the room description when we receive /look
            JsonObjectBuilder response = Json.createObjectBuilder();
            response.add(TYPE, LOCATION);
            response.add(NAME, name);
            response.add(DESCRIPTION, description);
            session.getBasicRemote().sendText("player," + userid + "," + response.build().toString());
            return;
        }

        // reject all unknown commands
        if (content.startsWith("/")) {
            sendMessageToRoom(session, null, "Unrecognised command - sorry :-(", userid);
            return;
        }

        // everything else is just chat.
        sendChatMessage(session, content, userid, username);
        return;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Reply methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void sendMessageToRoom(Session session, String messageForRoom, String messageForUser, String userid)
            throws IOException {
        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add(TYPE, "event");

        JsonObjectBuilder content = Json.createObjectBuilder();
        if (messageForRoom != null) {
            content.add("*", messageForRoom);
        }
        if (messageForUser != null) {
            content.add(userid, messageForUser);
        }

        response.add(CONTENT, content.build());
        response.add(BOOKMARK, bookmark++);

        String target = messageForRoom == null ? userid : "*";

        session.getBasicRemote().sendText("player," + target + "," + response.build().toString());
    }

    private void sendChatMessage(Session session, String message, String userid, String username) throws IOException {
        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add(TYPE, "chat");
        response.add(USERNAME, username);
        response.add(CONTENT, message);
        response.add(BOOKMARK, bookmark++);
        session.getBasicRemote().sendText("player," + "*" + "," + response.build().toString());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Util fns.
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private String[] splitRouting(String message) {
        ArrayList<String> list = new ArrayList<>();

        int brace = message.indexOf('{');
        int i = 0;
        int j = message.indexOf(',');
        while (j > 0 && j < brace) {
            list.add(message.substring(i, j));
            i = j + 1;
            j = message.indexOf(',', i);
        }
        list.add(message.substring(i));

        return list.toArray(new String[] {});
    }

    private static String getValue(JsonValue value) {
        if (value.getValueType().equals(ValueType.STRING)) {
            JsonString s = (JsonString) value;
            return s.getString();
        } else {
            return value.toString();
        }
    }

}
