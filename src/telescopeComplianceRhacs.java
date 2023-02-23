
//usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17
//JAVAC_OPTIONS -parameters
//DEPS io.quarkus.platform:quarkus-bom:2.16.2.Final@pom
//DEPS io.quarkus:quarkus-picocli
//DEPS io.quarkus:quarkus-jdbc-postgresql
//DEPS com.googlecode.json-simple:json-simple:1.1.1
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.*;
import java.io.*;
import java.sql.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.util.Set;
//quarkus.kubernetes-config.secrets=postgresql

@Command(name = "Greeting", mixinStandardHelpOptions = true)
public class telescopeComplianceRhacs implements Runnable {

//    String query = "SELECT * from integrations WHERE integration_id = ?";
    String query = "SELECT * from integrations,integration_methods WHERE integration_method_id = integration_methods.id AND integration_method_name = 'telescopeComplianceRhacs'";

    @Parameters(paramLabel = "<dbUsername>", defaultValue = "telescope", description = "Db username")
    String userName;
    //String userName = ${databaseUser};

    @Parameters(paramLabel = "<dbPassword>", defaultValue = "quarkus", description = "Db password")
    String password;
    //String = ${databasePassword};

    @Parameters(paramLabel = "<dbUrl>", defaultValue = "jdbc:postgresql://localhost:5432/telescope", description = "Db URL")
    String url;

    @Option(names = { "-v",
            "--verbose" }, description = "Verbose mode. Helpful for troubleshooting. Multiple -v options increase the verbosity.")
    private boolean verbose;

    Integer integration_id;
    Integer capability_id = 0;
    String acs_endpoint;
    String acs_token;
    Integer success_criteria;
    Integer success, failure = 0;
    Integer flag_id = 1;

    /**
     * Update the capability table with the new flag_id (1 = red, 2 = green)
     * 
     * @return the number of affected rows
     */
    public int setCapabilityWithFlag() {

        String query = "UPDATE capability "
                + "SET flag_id = ? "
                + "WHERE id = ?";

        int affectedrows = 0;

        try (Connection conn = DriverManager.getConnection(url, userName, password)) {

            PreparedStatement statement = conn.prepareStatement(query);
            statement.setInt(1, flag_id);
            statement.setInt(2, capability_id);
            affectedrows = statement.executeUpdate();

        } catch (SQLException ex) {
            System.out.println(ex.getMessage());
        }
        return affectedrows;
    }

    /**
     * Update the integrations table with last_update
     * 
     * @return the number of affected rows
     */
    public int setIntegrationLastUpdate() {
        String query = "UPDATE integrations "
                + "SET last_update = Now() "
                + "WHERE integration_id = ?";

        int affectedrows = 0;

        try (Connection conn = DriverManager.getConnection(url, userName, password)) {

            PreparedStatement statement = conn.prepareStatement(query);
            statement.setInt(1, integration_id);

            affectedrows = statement.executeUpdate();

        } catch (SQLException ex) {
            System.out.println(ex.getMessage());
        }
        return affectedrows;
    }

    /**
     * Update the flag depending the score.
     * 
     * @param StringBuilder responseData
     * @throws ParseException
     */
    public void setFlagDependingScore(StringBuilder responseData) throws ParseException {

        success = 0;
        failure = 0;

        JSONParser parser = new JSONParser();
        JSONObject responseJsonObject = (JSONObject) parser.parse(responseData.toString());
        JSONObject resultsJsonObject = (JSONObject) parser.parse(responseJsonObject.get("results").toString());
        JSONObject clusterResultsJsonObject = (JSONObject) resultsJsonObject.get("clusterResults");
        JSONObject controlResultsJsonObject = (JSONObject) clusterResultsJsonObject.get("controlResults");

        Set<?> keyset = controlResultsJsonObject.keySet();
        keyset.forEach(key -> {
            JSONObject value = (JSONObject) controlResultsJsonObject.get(key);
            success += value.get("overallState").equals("COMPLIANCE_STATE_SUCCESS") ? 1 : 0;
            failure += value.get("overallState").equals("COMPLIANCE_STATE_FAILURE") ? 1 : 0;

        });
        Integer total = success + failure;
        Double score = ((double) success / total) * 100;
        if (score >= success_criteria) {
            flag_id = 2;
        }

        if (verbose) {
            System.out.printf("flag id: %s\n", flag_id);
            System.out.printf("success: %s\n", success);
            System.out.printf("total: %s\n", total);
            System.out.printf("score: %s \n", score);
        }
    }

    /**
     * Retrieve data from database to process.
     * 
     */
    public void processData() {

        try (Connection conn = DriverManager.getConnection(url, userName, password)) {

            PreparedStatement statement = conn.prepareStatement(query);
            ResultSet rs = statement.executeQuery();
            {
                while (rs.next()) {
                    integration_id = rs.getInt("integration_id");
                    capability_id = rs.getInt("capability_id");
                    acs_endpoint = rs.getString("url");
                    acs_token = rs.getString("token");
                    success_criteria = rs.getInt("success_criteria");
                }

                if (verbose) {
                    System.out.printf("integration_id: %s\n", integration_id);
                    System.out.printf("capability_id: %s\n", capability_id);
                    System.out.printf("acs_endpoint: %s\n", acs_endpoint);
                    System.out.printf("acs_token: %s\n", acs_token);
                    System.out.printf("success_criteria: %s\n", success_criteria);
                }

                if (acs_endpoint != null) {

                    URL url = new URL(acs_endpoint);
                    HttpURLConnection con = (HttpURLConnection) url.openConnection();
                    con.setRequestMethod("GET");
                    con.setRequestProperty("Content-Type", "application/json");
                    con.setRequestProperty("Authorization", "Bearer ".concat(acs_token));
                    con.setRequestProperty("Accept", "application/json");

                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(con.getInputStream(), "utf-8"))) {
                        StringBuilder response = new StringBuilder();
                        String responseLine = null;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }
                        br.close();
                        setFlagDependingScore(response);
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        System.out.printf("Running RHACS compliance check for Telescope integration \n");

        processData();

        if (capability_id != 0) {
            setCapabilityWithFlag();
            setIntegrationLastUpdate();
                    System.out.printf("Compliance flag updated to: %s\n", flag_id);
        }
    }
}
