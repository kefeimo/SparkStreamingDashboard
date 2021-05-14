package KafkaLogsSimulator;


import java.sql.Timestamp;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;
import kafka.producer.ProducerConfig;

/**
 * Generates access logs by simulating user actions and sends the log lines to a Kafka topic.
 * Meant to be started as a Java application.
 *
 * Several arguments can be specified:
 * -brokerList=HOST1:PORT1,...   Required. Comma-separated list of Kafka broker host:port pairs.
 * -topic=NAME                   Optional. Name of the Kafka topic for sending log messages.
 * -usersToRun=NUM               Optional. Number of users (threads) to simulate. Default is 10.
 * -runSeconds=NUM               Optional. Number of seconds to run and simulate user traffic. Default is 120.
 * -thinkMin=NUM                 Optional. Minimum think time between simulated actions, in seconds. Default is 5.
 * -thinkMax=NUM                 Optional. Maximum think time between simulated actions, in seconds. Default is 10.
 * -silent                       Optional. Suppress all messages.
 * -help
 *
 */
public class LogSimulator extends Thread
{
    private static String TOPIC_NAME = "weblogs";
    private static String brokerList = null;
    private static Integer numberOfUsers = 10;
    private static Integer maxThinkTimeMillis = 10000;
    private static Integer minThinkTimeMillis = 5000;
    private static Integer timeToRunSecs = 120;  //120
    private static boolean silent = false;

    //used for monitoring number of active threads
    private static AtomicInteger threadCountMonitor = new AtomicInteger(0);
    //used for waiting
    private static Object syncObject = new Object();

    public static void main(String[] args)
    {
        parseArguments(args);

        Random rand = new Random();

        for(int i=0; i<numberOfUsers; i++)
        {
            LogSimulator user = new LogSimulator("192.168.1."+rand.nextInt(256), UUID.randomUUID().toString(), timeToRunSecs);
            user.start();
        }

        synchronized(syncObject)
        {
            int tcount = threadCountMonitor.get();
            do
            {
                try { syncObject.wait(2000); }
                catch(InterruptedException e) { }

                tcount = threadCountMonitor.get();

                if(!silent)
                    System.out.println("Waiting for "+tcount+" users to finish.");
            }
            while(tcount > 0);
        }

        if(!silent)
            System.out.println("All users have finished. Exiting...");
    }

    private static void parseArguments(String[] args)
    {
        try {
            for(String arg : args)
            {
                if(arg.startsWith("-topic="))
                    TOPIC_NAME = arg.substring(7);
                else if(arg.startsWith("-brokerList="))
                    brokerList = arg.substring(12);
                else if(arg.startsWith("-usersToRun="))
                    numberOfUsers = Integer.parseInt(arg.substring(12));
                else if(arg.startsWith("-thinkMin="))
                    minThinkTimeMillis = Integer.parseInt(arg.substring(10))*1000;
                else if(arg.startsWith("-thinkMax="))
                    maxThinkTimeMillis = Integer.parseInt(arg.substring(10))*1000;
                else if(arg.startsWith("-runSeconds="))
                    timeToRunSecs = Integer.parseInt(arg.substring(12));
                else if(arg.startsWith("-silent"))
                    silent = true;
                else if(arg.startsWith("-help"))
                    printUsageAndExit();
            }
        }
        catch(NumberFormatException e)
        {
            printUsageAndExit();
        }

        if(brokerList == null || brokerList.equals("") || TOPIC_NAME.equals("") ||
                numberOfUsers < 1 || minThinkTimeMillis < 1000 || maxThinkTimeMillis < 1000
                || timeToRunSecs < 1)
            printUsageAndExit();
    }

    private static void printUsageAndExit()
    {
        System.err.println();
        System.err.println("Possible arguments:");
        System.err.println("  -brokerList=HOST1:PORT1,...   Required. Comma-separated list of Kafka broker host:port pairs.");
        System.err.println("  -topic=NAME                   Optional. Name of the Kafka topic for sending log messages.");
        System.err.println("  -usersToRun=NUM               Optional. Number of users to simulate. Default is 10.");
        System.err.println("  -runSeconds=NUM               Optional. Number of seconds to run and simulate user traffic. Default is 120.");
        System.err.println("  -thinkMin=NUM                 Optional. Minimum think time between simulated actions, in seconds. Default is 5.");
        System.err.println("  -thinkMax=NUM                 Optional. Maximum think time between simulated actions, in seconds. Default is 10.");
        System.err.println("  -silent                       Optional. Suppress all messages.");
        System.err.println("  -help                         Print this message and exit.");
        System.exit(1);
    }


    //IP address of this thread (simulated user)
    private String ipAddress;
    //session id of this thread
    private String sessionId;
    //how many seconds to be active
    private int timeToRun;
    //used for waiting
    private Object thinkTimeSyncObj = new Object();
    private Random random = new Random();

    /**
     *
     * @param ipAdress IP address for access log events generated by this thread
     * @param sessionId Session id for access log events generated by this thread
     * @param timeToRun Specifies how many seconds should this thread be active and generate events
     */
    public LogSimulator(String ipAdress, String sessionId, int timeToRun)
    {
        this.ipAddress = ipAdress;
        this.sessionId = sessionId;
        this.timeToRun = timeToRun;
    }

    public void run()
    {
        try {
            if(!silent)
                System.out.println("Starting user simulator with parameters: IP="+ipAddress+", sessionId="+sessionId+", timeToRun="+timeToRun);

            threadCountMonitor.incrementAndGet();

            Properties props = new Properties();

            props.put("metadata.broker.list", brokerList);
            props.put("serializer.class", "kafka.serializer.StringEncoder");
            props.put("partitioner.class", "KafkaLogsSimulator.IPPartitioner");
            props.put("request.required.acks", "1");
//            props.put("key.serializer",
//                    "org.apache.kafka.common.serialization.StringSerializer");
//            props.put("value.serializer",
//                    "org.apache.kafka.common.serialization.StringSerializer");

            ProducerConfig config = new ProducerConfig(props);

            Producer<String, String> producer = new Producer<String, String>(config);

            long startTime = System.currentTimeMillis();

            while(true)
            {
                long currTime = System.currentTimeMillis();
                //is the time up?
                if(currTime - startTime >= timeToRun * 1000)
                    break;

                Timestamp t = new Timestamp(currTime);
                String url = getNextUrl();

                String respCode = "200";
                double rnd = random.nextDouble();
                //generate an error for 2% of all events
                if(rnd > 0.98)
                    respCode = "404";

                //message has the following format: date time IPAddress sessionId URL method responseCode responseTime
                String message = t.toString()+" "+ipAddress+" "+sessionId+" "+url+" GET "+respCode+" 500";

                if(!silent)
                    System.err.println(message);

                //send the message to Kafka
                KeyedMessage<String, String> data = new KeyedMessage<String, String>(TOPIC_NAME, ipAddress, message);
//                System.out.println(data);
                producer.send(data);

                //wait for random think time
                synchronized(thinkTimeSyncObj)
                {
                    try { thinkTimeSyncObj.wait(minThinkTimeMillis +
                            Double.valueOf(random.nextDouble()*(maxThinkTimeMillis - minThinkTimeMillis)).longValue()); }
                    catch(InterruptedException ie) { }
                }
            }//while

            producer.close();

            if(!silent)
                System.out.println("Stopping user simulator with sessionId "+sessionId);

            threadCountMonitor.decrementAndGet();
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Produces the next URL. For now, generates a click on an ad for 3% of cases.
     * Three ad categories 1, 2 and 3 are equally possible (1% each).
     *
     * For other 97% of cases returns just "/" because non-ad URLs are not further analyzed currently.
     *
     * @return The generated URL.
     */
    private String getNextUrl()
    {
        double rd = random.nextDouble();
        if(rd > 0.9)
            return "sia.org/ads/1/123/clickfw";
        else if(rd > 0.8)
            return "sia.org/ads/2/234/clickfw";
        else if(rd > 0.7)
            return "sia.org/ads/3/56/clickfw";
        return "/";
    }
}
