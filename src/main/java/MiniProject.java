import be.ugent.rml.Executor;
import be.ugent.rml.Utils;
import be.ugent.rml.functions.FunctionLoader;
import be.ugent.rml.functions.lib.IDLabFunctions;
import be.ugent.rml.records.RecordsFactory;
import be.ugent.rml.store.QuadStore;
import be.ugent.rml.store.QuadStoreFactory;
import be.ugent.rml.store.RDF4JStore;
import be.ugent.rml.term.NamedNode;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.query.Update;
import java.io.File;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

public class MiniProject {
    public static void main(String[] args) {

        // 1. APPLY RML RULES //

        String rootFolder = "./src/main/resources/";
        String mappingFile = "nba_mapping.ttl";
        String outputFile = "nba_output.ttl";

        try {
            // Path to the mapping file that needs to be executed
            File mappingFileObj = new File(rootFolder + mappingFile);

            // Get the mapping string stream
            InputStream mappingStream = new FileInputStream(mappingFileObj);

            // Load the mapping in a QuadStore
            QuadStore rmlStore = QuadStoreFactory.read(mappingStream);

            // Set up the basepath for the records factory, i.e., the basepath for the (local file) data sources
            RecordsFactory factory = new RecordsFactory(mappingFileObj.getParent());

            // Set up the functions used during the mapping
            Map<String, Class> libraryMap = new HashMap<>();
            libraryMap.put("IDLabFunctions", IDLabFunctions.class);

            FunctionLoader functionLoader = new FunctionLoader(null, libraryMap);

            // Set up the outputstore (needed when you want to output something else than nquads
            QuadStore outputStore = new RDF4JStore();

            // Create executor
            Executor executor = new Executor(rmlStore, factory, functionLoader, outputStore, Utils.getBaseDirectiveTurtle(mappingStream));

            // Execute the mapping
            QuadStore result = executor.executeV5(null).get(new NamedNode("rmlmapper://default.store"));

            // Output the result in console
            // BufferedWriter consoleOut = new BufferedWriter(new OutputStreamWriter(System.out));
            // result.write(consoleOut, "turtle");
            // consoleOut.close();

            // Output the results in a file
            Writer fileOut = new FileWriter(rootFolder + outputFile);
            result.write(fileOut, "turtle");
            fileOut.close();

            System.out.println("\nRML rules executed successfully.");

        } catch (Exception e) {
            e.printStackTrace();
        }


        // 2. CONNECT AND UPLOAD TO GRAPHDB //

        // Connect to the GraphDB repository "MiniProject"
        HTTPRepository repository = new HTTPRepository("http://localhost:7200/repositories/MiniProject");
        RepositoryConnection connection = repository.getConnection();

        // Clear repository
        connection.clear();

        // Load ontology file
        try (FileInputStream ontologyStream = new FileInputStream("src/main/resources/nba_ontology.ttl")) {
            connection.begin();
            connection.add(ontologyStream, "urn:ontology", RDFFormat.TURTLE);
            connection.commit();
            System.out.println("Ontology loaded successfully.");
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Load data file
        try (FileInputStream dataStream = new FileInputStream("src/main/resources/nba_output.ttl")) {
            connection.begin();
            connection.add(dataStream, "urn:data", RDFFormat.TURTLE);
            connection.commit();
            System.out.println("Data loaded successfully.");
        } catch (IOException e) {
            e.printStackTrace();
        }


        // 3.SPARQL QUERY EXECUTION //

        // Query 1: How many players came from each country
        String queryString = """
                PREFIX : <http://example.org/nba#>
                
                SELECT ?countryName (COUNT(?player) AS ?numPlayers)
                WHERE {
                  ?player a :Player ;\s
                    :from_country ?country .
                 \s
                  ?country :country_name ?countryName .
                }
                GROUP BY ?countryName
                ORDER BY DESC(?numPlayers)
                """;

        TupleQuery query1 = connection.prepareTupleQuery(queryString);

        // Print results
        try (TupleQueryResult result = query1.evaluate()) {
            System.out.println("\nSPARQL Query 1 Results:");
            while (result.hasNext()) {
                BindingSet solution = result.next();
                System.out.println("Country: " + solution.getValue("countryName")
                        + ", Number of Players: " + solution.getValue("numPlayers"));
            }
        }

        // Query 2: How many players joined a team during the year 2020
        queryString = """
                PREFIX : <http://example.org/nba#>
                
                SELECT ?teamName (COUNT(?player) AS ?numPlayers)
                WHERE {
                  ?team a :Team ;
                    :full_name ?teamName ;
                    :has_player ?player .
                
                  ?player a :Player ;
                    :from_year ?fromYear .
                
                  FILTER(?fromYear = "2020.0")
                }
                GROUP BY ?teamName
                ORDER BY ?teamName
                """;

        TupleQuery query2 = connection.prepareTupleQuery(queryString);

        // Print results
        try (TupleQueryResult result = query2.evaluate()) {
            System.out.println("\nSPARQL Query 2 Results:");
            while (result.hasNext()) {
                BindingSet solution = result.next();
                System.out.println("Team: " + solution.getValue("teamName")
                        + ", Number of Players: " + solution.getValue("numPlayers"));
            }
        }

        // Query 3: How many games did a team win during the 2019-20 NBA season
        queryString = """
                PREFIX : <http://example.org/nba#>
                
                SELECT ?teamName (COUNT(?game) AS ?wins)
                WHERE {
                  ?team a :Team ;
                    :full_name ?teamName .
                
                  {
                    # Team is the home team and won
                    ?game :home_team ?team ;
                        :won_lost_by_home "W" ;
                        :game_date ?date .
                  }
                  UNION
                  {
                    # Team is the away team and won
                    ?game :away_team ?team ;
                        :won_lost_by_away "W" ;
                        :game_date ?date .
                  }
                
                  # Filter for games during the 2019–2020 NBA season
                  FILTER(?date >= "2019-10-22T00:00:00" &&
                         ?date < "2020-03-11T00:00:00")
                }
                GROUP BY ?teamName
                ORDER BY DESC(?wins)
                """;

        TupleQuery query3 = connection.prepareTupleQuery(queryString);

        // Print results
        try (TupleQueryResult result = query3.evaluate()) {
            System.out.println("\nSPARQL Query 3 Results:");
            while (result.hasNext()) {
                BindingSet solution = result.next();
                System.out.println("Team: " + solution.getValue("teamName")
                        + ", Wins: " + solution.getValue("wins"));
            }
        }

        // Update Query 1
        String updateQuery = """
                PREFIX : <http://example.org/nba#>
        
                INSERT {
                  ?player :first_and_last_name ?fullName .
                }
                WHERE {
                  ?player a :Player ;
                          :first_name ?firstName ;
                          :last_name ?lastName .
                  BIND(CONCAT(?firstName, " ", ?lastName) AS ?fullName)
                }
                """;

        // Prepare and execute the update
        Update update1 = connection.prepareUpdate(updateQuery);
        update1.execute();
        System.out.println("\nSPARQL Update Query 1 executed successfully.");

        // Update Query 2
        updateQuery = """
                PREFIX : <http://example.org/nba#>
                
                INSERT {
                  ?game :in_arena ?arena .
                }
                WHERE {
                  ?game a :Game ;
                    :home_team ?team .
                
                  ?team :has_arena ?arena .
                }
                """;

        // Prepare and execute the update
        Update update2 = connection.prepareUpdate(updateQuery);
        update2.execute();
        System.out.println("\nSPARQL Update Query 2 executed successfully.");

        connection.close();
        repository.shutDown();
    }
}
