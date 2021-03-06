package akka.docs.config

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

//#imports
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions

//#imports

class ConfigDocSpec extends WordSpec {

  "programmatically configure ActorSystem" in {
    //#custom-config
    val customConf = ConfigFactory.parseString("""
      akka.actor.deployment {
        /app/my-service {
          router = round-robin
          nr-of-instances = 3
        }
      }
      """, ConfigParseOptions.defaults)
    val system = ActorSystem("MySystem", ConfigFactory.systemProperties.withFallback(customConf))
    //#custom-config

    system.stop()

  }

}
