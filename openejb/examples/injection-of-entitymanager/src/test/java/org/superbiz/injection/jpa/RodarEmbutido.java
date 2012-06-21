/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.superbiz.injection.jpa;

import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.ejb.embeddable.EJBContainer;
import javax.naming.Context;
import javax.naming.NamingException;

/**
 *
 * @author leonardo
 */
public class RodarEmbutido {

  public static void main(String[] args) {
    try {
      final Properties p = new Properties();
      p.put("movieDatabase", "new://Resource?type=DataSource");
      p.put("movieDatabase.JdbcDriver", "org.hsqldb.jdbcDriver");
      p.put("movieDatabase.JdbcUrl", "jdbc:hsqldb:mem:moviedb");

      EJBContainer container = EJBContainer.createEJBContainer(p);
      final Context context = container.getContext();

      Movies movies = (Movies) context.lookup("java:global/injection-of-entitymanager/Movies");

      movies.addMovie(new Movie("Quentin Tarantino", "Reservoir Dogs", 1992));
      movies.addMovie(new Movie("Joel Coen", "Fargo", 1996));
      movies.addMovie(new Movie("Joel Coen", "The Big Lebowski", 1998));

      List<Movie> list = movies.getMovies();
      //assertEquals("List.size()", 3, list.size());

      for (Movie movie : list) {
          System.out.format(" ---- %s %s %s    ----\n", movie.getDirector(), movie.getTitle(), movie.getYear() );
          movies.deleteMovie(movie);
      }

      //assertEquals("Movies.getMovies()", 0, movies.getMovies().size());

      container.close();
    } catch (Exception ex) {
      Logger.getLogger(RodarEmbutido.class.getName()).log(Level.SEVERE, null, ex);
//    } catch (NamingException ex) {
//      Logger.getLogger(RodarEmbutido.class.getName()).log(Level.SEVERE, null, ex);
    }
  }

}
