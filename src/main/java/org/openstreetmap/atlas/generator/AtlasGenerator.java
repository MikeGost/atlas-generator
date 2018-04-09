package org.openstreetmap.atlas.generator;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.PairFunction;
import org.openstreetmap.atlas.exception.CoreException;
import org.openstreetmap.atlas.generator.persistence.MultipleAtlasCountryStatisticsOutputFormat;
import org.openstreetmap.atlas.generator.persistence.MultipleAtlasOutputFormat;
import org.openstreetmap.atlas.generator.persistence.MultipleAtlasStatisticsOutputFormat;
import org.openstreetmap.atlas.generator.persistence.delta.RemovedMultipleAtlasDeltaOutputFormat;
import org.openstreetmap.atlas.generator.sharding.AtlasSharding;
import org.openstreetmap.atlas.generator.tools.filesystem.FileSystemHelper;
import org.openstreetmap.atlas.generator.tools.spark.SparkJob;
import org.openstreetmap.atlas.geography.atlas.Atlas;
import org.openstreetmap.atlas.geography.atlas.delta.AtlasDelta;
import org.openstreetmap.atlas.geography.atlas.pbf.AtlasLoadingOption;
import org.openstreetmap.atlas.geography.atlas.raw.sectioning.WaySectionProcessor;
import org.openstreetmap.atlas.geography.atlas.raw.slicing.RawAtlasCountrySlicer;
import org.openstreetmap.atlas.geography.atlas.statistics.AtlasStatistics;
import org.openstreetmap.atlas.geography.atlas.statistics.Counter;
import org.openstreetmap.atlas.geography.boundary.CountryBoundary;
import org.openstreetmap.atlas.geography.boundary.CountryBoundaryMap;
import org.openstreetmap.atlas.geography.boundary.CountryBoundaryMapArchiver;
import org.openstreetmap.atlas.geography.sharding.CountryShard;
import org.openstreetmap.atlas.geography.sharding.Shard;
import org.openstreetmap.atlas.geography.sharding.Sharding;
import org.openstreetmap.atlas.locale.IsoCountry;
import org.openstreetmap.atlas.streaming.resource.Resource;
import org.openstreetmap.atlas.tags.filters.ConfiguredTaggableFilter;
import org.openstreetmap.atlas.utilities.collections.StringList;
import org.openstreetmap.atlas.utilities.configuration.StandardConfiguration;
import org.openstreetmap.atlas.utilities.conversion.StringConverter;
import org.openstreetmap.atlas.utilities.maps.MultiMapWithSet;
import org.openstreetmap.atlas.utilities.runtime.CommandMap;
import org.openstreetmap.atlas.utilities.runtime.system.memory.Memory;
import org.openstreetmap.atlas.utilities.threads.Pool;
import org.openstreetmap.atlas.utilities.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

import scala.Tuple2;

/**
 * Generate {@link Atlas} Shards for a specific version and a specific set of countries
 *
 * @author matthieun
 * @author mgostintsev
 */
public class AtlasGenerator extends SparkJob
{
    /**
     * @author matthieun
     */
    @SuppressWarnings("unused")
    private static class CountrySplitter implements Serializable, Function<String, String>
    {
        private static final long serialVersionUID = 6984474253285033371L;

        @Override
        public String apply(final String key)
        {
            return StringList.split(key, CountryShard.COUNTRY_SHARD_SEPARATOR).get(0);
        }
    }

    private static final long serialVersionUID = 5985696743749843135L;

    private static final Logger logger = LoggerFactory.getLogger(AtlasGenerator.class);
    public static final String ATLAS_FOLDER = "atlas";
    public static final String RAW_ATLAS_FOLDER = "rawAtlas";
    public static final String SLICED_RAW_ATLAS_FOLDER = "slicedRawAtlas";
    public static final String SHARD_STATISTICS_FOLDER = "shardStats";
    public static final String COUNTRY_STATISTICS_FOLDER = "countryStats";
    public static final String SHARD_DELTAS_FOLDER = "deltas";
    public static final String SHARD_DELTAS_ADDED_FOLDER = "deltasAdded";
    public static final String SHARD_DELTAS_CHANGED_FOLDER = "deltasChanged";
    public static final String SHARD_DELTAS_REMOVED_FOLDER = "deltasRemoved";

    private static final String SHARDING_DEFAULT = "slippy@10";

    public static final Switch<StringList> COUNTRIES = new Switch<>("countries",
            "Comma separated list of countries to be included in the final Atlas",
            value -> StringList.split(value, ","), Optionality.REQUIRED);
    public static final Switch<String> COUNTRY_SHAPES = new Switch<>("countryShapes",
            "Shape file containing the countries", StringConverter.IDENTITY, Optionality.REQUIRED);
    public static final Switch<String> PBF_PATH = new Switch<>("pbfs", "The path to PBFs",
            StringConverter.IDENTITY, Optionality.REQUIRED);
    public static final Switch<String> PBF_SCHEME = new Switch<>("pbfScheme",
            "The folder structure of the PBF", StringConverter.IDENTITY, Optionality.OPTIONAL,
            PbfLocator.DEFAULT_SCHEME);
    public static final Switch<String> PBF_SHARDING = new Switch<>("pbfSharding",
            "The sharding tree of the pbf files. If not specified, this will default to the general Atlas sharding.",
            StringConverter.IDENTITY, Optionality.OPTIONAL);
    public static final Switch<String> SHARDING_TYPE = new Switch<>("sharding",
            "The sharding definition.", StringConverter.IDENTITY, Optionality.OPTIONAL,
            SHARDING_DEFAULT);
    public static final Switch<String> PREVIOUS_OUTPUT_FOR_DELTA = new Switch<>(
            "previousOutputForDelta",
            "The path of the output of the previous job that can be used for delta computation",
            StringConverter.IDENTITY, Optionality.OPTIONAL, "");
    public static final Switch<String> CODE_VERSION = new Switch<>("codeVersion",
            "The code version", StringConverter.IDENTITY, Optionality.OPTIONAL, "unknown");
    public static final Switch<String> DATA_VERSION = new Switch<>("dataVersion",
            "The data version", StringConverter.IDENTITY, Optionality.OPTIONAL, "unknown");
    public static final Switch<String> EDGE_CONFIGURATION = new Switch<>("edgeConfiguration",
            "The path to the configuration file that defines what OSM Way becomes an Edge",
            StringConverter.IDENTITY, Optionality.OPTIONAL);
    public static final Switch<String> WAY_SECTIONING_CONFIGURATION = new Switch<>(
            "waySectioningConfiguration",
            "The path to the configuration file that defines where to section Ways to make Edges.",
            StringConverter.IDENTITY, Optionality.OPTIONAL);
    public static final Switch<String> PBF_WAY_CONFIGURATION = new Switch<>(
            "osmPbfWayConfiguration",
            "The path to the configuration file that defines which PBF Ways becomes an Atlas Entity.",
            StringConverter.IDENTITY, Optionality.OPTIONAL);
    public static final Switch<String> PBF_NODE_CONFIGURATION = new Switch<>(
            "osmPbfNodeConfiguration",
            "The path to the configuration file that defines which PBF Nodes becomes an Atlas Entity.",
            StringConverter.IDENTITY, Optionality.OPTIONAL);
    public static final Switch<String> PBF_RELATION_CONFIGURATION = new Switch<>(
            "osmPbfRelationConfiguration",
            "The path to the configuration file that defines which PBF Relations becomes an Atlas Entity",
            StringConverter.IDENTITY, Optionality.OPTIONAL);
    // TODO - once this is fully baked and tested, remove flag and old flow
    private static final Flag USE_RAW_ATLAS = new Flag("useRawAtlas",
            "Allow PBF to Atlas process to use Raw Atlas flow");

    public static void main(final String[] args)
    {
        new AtlasGenerator().run(args);
    }

    /**
     * Generates a {@link List} of {@link AtlasGenerationTask}s for given countries using given
     * {@link CountryBoundaryMap} and {@link Sharding} strategy.
     *
     * @param countries
     *            Countries to generate tasks for
     * @param boundaryMap
     *            {@link CountryBoundaryMap} to read country boundaries
     * @param sharding
     *            {@link Sharding} strategy
     * @return {@link List} of {@link AtlasGenerationTask}s
     */
    protected static List<AtlasGenerationTask> generateTasks(final StringList countries,
            final CountryBoundaryMap boundaryMap, final Sharding sharding)
    {
        // Extract country boundaries and queue them
        final BlockingQueue<CountryBoundary> queue = new LinkedBlockingQueue<>();
        final MultiMapWithSet<String, Shard> countryToShardMap = new MultiMapWithSet<>();
        countries.stream().forEach(country ->
        {
            // Initialize country-shard map
            countryToShardMap.put(country, new HashSet<>());

            // Fetch boundaries
            final List<CountryBoundary> countryBoundaries = boundaryMap.countryBoundary(country);
            if (countryBoundaries == null)
            {
                logger.error("No boundaries found for {}!", country);
                return;
            }

            // Queue boundary for processing
            countryBoundaries.forEach(queue::add);
        });

        // Use all available processors except one (used by main thread)
        final int threadCount = Runtime.getRuntime().availableProcessors() - 1;
        logger.info("Generating tasks with {} processors (threads).", threadCount);

        // Start the execution pool to generate tasks
        try (Pool processPool = new Pool(threadCount, "Atlas Task Generator"))
        {
            // Generate processors
            IntStream.range(0, threadCount).forEach(index ->
            {
                processPool.queue(new AtlasGeneratorTaskProcessor(queue, sharding, boundaryMap,
                        countryToShardMap));
            });
        }

        // Generate tasks from country-shard map
        final List<AtlasGenerationTask> tasks = new ArrayList<>();
        countryToShardMap.keySet().forEach(country ->
        {
            final Set<Shard> shards = countryToShardMap.get(country);
            if (!shards.isEmpty())
            {
                shards.forEach(shard -> tasks.add(new AtlasGenerationTask(country, shard, shards)));
            }
            else
            {
                logger.warn("No shards were found for {}. Skipping task generation.", country);
            }
        });

        return tasks;
    }

    protected static StandardConfiguration getStandardConfigurationFrom(final String path,
            final Map<String, String> configuration)
    {
        return new StandardConfiguration(FileSystemHelper.resource(path, configuration));
    }

    private static AtlasLoadingOption buildAtlasLoadingOption(final CountryBoundaryMap boundaries,
            final Map<String, String> sparkContext, final Map<String, String> properties)
    {
        final AtlasLoadingOption atlasLoadingOption = AtlasLoadingOption
                .createOptionWithAllEnabled(boundaries);

        // Apply all configurations
        final String edgeConfiguration = properties.get(EDGE_CONFIGURATION.getName());
        if (edgeConfiguration != null)
        {
            atlasLoadingOption
                    .setEdgeFilter(getTaggableFilterFrom(edgeConfiguration, sparkContext));
        }

        final String waySectioningConfiguration = properties
                .get(WAY_SECTIONING_CONFIGURATION.getName());
        if (waySectioningConfiguration != null)
        {
            atlasLoadingOption.setWaySectionFilter(
                    getTaggableFilterFrom(waySectioningConfiguration, sparkContext));
        }

        final String pbfNodeConfiguration = properties.get(PBF_NODE_CONFIGURATION.getName());
        if (pbfNodeConfiguration != null)
        {
            atlasLoadingOption
                    .setOsmPbfNodeFilter(getTaggableFilterFrom(pbfNodeConfiguration, sparkContext));
        }

        final String pbfWayConfiguration = properties.get(PBF_WAY_CONFIGURATION.getName());
        if (pbfWayConfiguration != null)
        {
            atlasLoadingOption
                    .setOsmPbfWayFilter(getTaggableFilterFrom(pbfWayConfiguration, sparkContext));
        }

        final String pbfRelationConfiguration = properties
                .get(PBF_RELATION_CONFIGURATION.getName());
        if (pbfRelationConfiguration != null)
        {
            atlasLoadingOption.setOsmPbfRelationFilter(
                    getTaggableFilterFrom(pbfRelationConfiguration, sparkContext));
        }

        return atlasLoadingOption;
    }

    private static Map<String, String> extractAtlasLoadingProperties(final CommandMap command)
    {
        final Map<String, String> propertyMap = Maps.newHashMap();
        propertyMap.put(CODE_VERSION.getName(), (String) command.get(CODE_VERSION));
        propertyMap.put(DATA_VERSION.getName(), (String) command.get(DATA_VERSION));
        propertyMap.put(EDGE_CONFIGURATION.getName(), (String) command.get(EDGE_CONFIGURATION));
        propertyMap.put(WAY_SECTIONING_CONFIGURATION.getName(),
                (String) command.get(WAY_SECTIONING_CONFIGURATION));
        propertyMap.put(PBF_NODE_CONFIGURATION.getName(),
                (String) command.get(PBF_NODE_CONFIGURATION));
        propertyMap.put(PBF_WAY_CONFIGURATION.getName(),
                (String) command.get(PBF_WAY_CONFIGURATION));
        propertyMap.put(PBF_RELATION_CONFIGURATION.getName(),
                (String) command.get(PBF_RELATION_CONFIGURATION));
        propertyMap.put(CODE_VERSION.getName(), (String) command.get(CODE_VERSION));
        propertyMap.put(DATA_VERSION.getName(), (String) command.get(DATA_VERSION));

        return propertyMap;
    }

    private static ConfiguredTaggableFilter getTaggableFilterFrom(final String path,
            final Map<String, String> configuration)
    {
        return new ConfiguredTaggableFilter(getStandardConfigurationFrom(path, configuration));
    }

    @Override
    public String getName()
    {
        return "Atlas Generator";
    }

    @Override
    public void start(final CommandMap command)
    {
        // logger.info("***********************Classpath:**********************");
        // final ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        // final URL[] urls = ((URLClassLoader) classLoader).getURLs();
        // for (final URL url : urls)
        // {
        // logger.info(url.getFile());
        // final File file = new File(url.getFile());
        // if (file.isDirectory())
        // {
        // file.listFilesRecursively()
        // .forEach(sub -> logger.info("\t{}", sub.getAbsolutePath()));
        // }
        // }
        // logger.info("*******************************************************");
        // final Class clazz = DOMParser.class;
        // logger.info("##########$$$$$$$$$--" + clazz.getSimpleName() + ": "
        // + clazz.getProtectionDomain().getCodeSource() + ": "
        // + clazz.getProtectionDomain().getCodeSource().getLocation());
        final StringList countries = (StringList) command.get(COUNTRIES);
        final String countryShapes = (String) command.get(COUNTRY_SHAPES);
        final String previousOutputForDelta = (String) command.get(PREVIOUS_OUTPUT_FOR_DELTA);
        final String pbfPath = (String) command.get(PBF_PATH);
        final String pbfScheme = (String) command.get(PBF_SCHEME);
        final String pbfShardingName = (String) command.get(PBF_SHARDING);
        final String shardingName = (String) command.get(SHARDING_TYPE);
        final Sharding sharding = AtlasSharding.forString(shardingName, configuration());
        final Sharding pbfSharding = pbfShardingName != null
                ? AtlasSharding.forString(shardingName, configuration()) : sharding;
        final PbfContext pbfContext = new PbfContext(pbfPath, pbfSharding, pbfScheme);
        final String codeVersion = (String) command.get(CODE_VERSION);
        final String dataVersion = (String) command.get(DATA_VERSION);
        final String edgeConfiguration = (String) command.get(EDGE_CONFIGURATION);
        final String waySectioningConfiguration = (String) command
                .get(WAY_SECTIONING_CONFIGURATION);
        final String pbfNodeConfiguration = (String) command.get(PBF_NODE_CONFIGURATION);
        final String pbfWayConfiguration = (String) command.get(PBF_WAY_CONFIGURATION);
        final String pbfRelationConfiguration = (String) command.get(PBF_RELATION_CONFIGURATION);
        final boolean useRawAtlas = (boolean) command.get(USE_RAW_ATLAS);

        final String output = output(command);
        final Map<String, String> sparkContext = configurationMap();

        // This has to be converted here, as we need the Spark Context
        final Resource countryBoundaries = resource(countryShapes);

        logger.info("Reading country boundaries from {}", countryShapes);
        final CountryBoundaryMap boundaries = new CountryBoundaryMapArchiver()
                .read(countryBoundaries);
        logger.info("Done Reading {} country boundaries from {}", boundaries.size(), countryShapes);
        if (!boundaries.hasGridIndex())
        {
            logger.warn(
                    "Given country boundary file didn't have grid index. Initializing grid index for {}.",
                    countries);
            boundaries.initializeGridIndex(countries.stream().collect(Collectors.toSet()));
        }

        // Generate country-shard generation tasks
        final Time timer = Time.now();
        final List<AtlasGenerationTask> tasks = generateTasks(countries, boundaries, sharding);
        logger.debug("Generated {} tasks in {}.", tasks.size(), timer.elapsedSince());

        if (useRawAtlas)
        {
            // AtlasLoadingOption isn't serializable, neither is command map. To avoid duplicating
            // boiler-plate code for creating the AtlasLoadingOption, extract the properties we need
            // from the command map and pass those around to create the AtlasLoadingOption
            final Map<String, String> atlasLoadingOptions = extractAtlasLoadingProperties(command);

            // Generate the raw Atlas and filter any null atlases
            final JavaPairRDD<String, Atlas> countryRawAtlasShardsRDD = getContext()
                    .parallelize(tasks, tasks.size()).mapToPair(this.generateRawAtlas(boundaries,
                            sparkContext, atlasLoadingOptions, pbfContext))
                    .filter(tuple -> tuple._2() != null);

            // Persist the RDD and save the intermediary state
            countryRawAtlasShardsRDD.cache();
            countryRawAtlasShardsRDD.saveAsHadoopFile(
                    getAlternateSubFolderOutput(output, RAW_ATLAS_FOLDER), Text.class, Atlas.class,
                    MultipleAtlasOutputFormat.class, new JobConf(configuration()));
            logger.info("\n\n********** SAVED THE RAW ATLAS **********\n");

            // Slice the raw Atlas and filter any null atlases
            final JavaPairRDD<String, Atlas> countrySlicedRawAtlasShardsRDD = countryRawAtlasShardsRDD
                    .mapToPair(this.sliceRawAtlas(boundaries)).filter(tuple -> tuple._2() != null);

            // Persist the RDD and save the intermediary state
            countrySlicedRawAtlasShardsRDD.cache();
            countrySlicedRawAtlasShardsRDD.saveAsHadoopFile(
                    getAlternateSubFolderOutput(output, SLICED_RAW_ATLAS_FOLDER), Text.class,
                    Atlas.class, MultipleAtlasOutputFormat.class, new JobConf(configuration()));
            logger.info("\n\n********** SAVED THE SLICED RAW ATLAS **********\n");

            // Section the sliced raw Atlas
            final JavaPairRDD<String, Atlas> countryAtlasShardsRDD = countrySlicedRawAtlasShardsRDD
                    .mapToPair(this.sectionRawAtlas(boundaries, sparkContext, atlasLoadingOptions));

            // Save the result, one for each key
            countryAtlasShardsRDD.saveAsHadoopFile(
                    getAlternateSubFolderOutput(output, ATLAS_FOLDER), Text.class, Atlas.class,
                    MultipleAtlasOutputFormat.class, new JobConf(configuration()));
            logger.info("\n\n********** SAVED THE FINAL ATLAS **********\n");

            // TODO Metrics, Deltas and Statistics for final Atlas
        }
        else
        {
            // Transform the map country name to shard to country name to Atlas
            // This is not enforced, but it has to be a 1-1 mapping here.
            final Map<String, String> configurationMap = configurationMap();
            final JavaPairRDD<String, Atlas> countryAtlasShardsRDD = getContext()
                    .parallelize(tasks, tasks.size()).mapToPair(task ->
                    {
                        // Get the country name
                        final String countryName = task.getCountry();
                        // Get the country shard
                        final Shard shard = task.getShard();
                        // Build the AtlasLoadingOption
                        final AtlasLoadingOption atlasLoadingOption = AtlasLoadingOption
                                .createOptionWithAllEnabled(boundaries)
                                .setAdditionalCountryCodes(countryName);

                        // Apply all configurations
                        if (edgeConfiguration != null)
                        {
                            atlasLoadingOption.setEdgeFilter(
                                    getTaggableFilterFrom(edgeConfiguration, configurationMap));
                        }
                        if (waySectioningConfiguration != null)
                        {
                            atlasLoadingOption.setWaySectionFilter(getTaggableFilterFrom(
                                    waySectioningConfiguration, configurationMap));
                        }
                        if (pbfNodeConfiguration != null)
                        {
                            atlasLoadingOption.setOsmPbfNodeFilter(
                                    getTaggableFilterFrom(pbfNodeConfiguration, configurationMap));
                        }
                        if (pbfWayConfiguration != null)
                        {
                            atlasLoadingOption.setOsmPbfWayFilter(
                                    getTaggableFilterFrom(pbfWayConfiguration, configurationMap));
                        }
                        if (pbfRelationConfiguration != null)
                        {
                            atlasLoadingOption.setOsmPbfRelationFilter(getTaggableFilterFrom(
                                    pbfRelationConfiguration, configurationMap));
                        }

                        // Build the appropriate PbfLoader
                        final PbfLoader loader = new PbfLoader(pbfContext, sparkContext, boundaries,
                                atlasLoadingOption, codeVersion, dataVersion, task.getAllShards());
                        final String name = countryName + CountryShard.COUNTRY_SHARD_SEPARATOR
                                + shard.getName();
                        final Atlas atlas;
                        try
                        {
                            // Generate the Atlas for this shard
                            atlas = loader.load(countryName, shard);
                        }
                        catch (final Throwable e)
                        {
                            throw new CoreException("Building Atlas {} failed!", name, e);
                        }

                        // Report on memory usage
                        logger.info("Printing memory after loading Atlas {}", name);
                        Memory.printCurrentMemory();
                        // Output the Name/Atlas couple
                        final Tuple2<String, Atlas> result = new Tuple2<>(name, atlas);
                        return result;
                    });

            // Filter out null Atlas.
            final JavaPairRDD<String, Atlas> countryNonNullAtlasShardsRDD = countryAtlasShardsRDD
                    .filter(tuple -> tuple._2() != null);

            // Cache the Atlas
            countryNonNullAtlasShardsRDD.cache();
            logger.info("\n\n********** CACHED THE ATLAS **********\n");

            // Run the metrics
            final JavaPairRDD<String, AtlasStatistics> statisticsRDD = countryNonNullAtlasShardsRDD
                    .mapToPair(tuple ->
                    {
                        final Counter counter = new Counter().withSharding(sharding);
                        counter.setCountsDefinition(Counter.POI_COUNTS_DEFINITION.getDefault());
                        final AtlasStatistics statistics;
                        try
                        {
                            statistics = counter.processAtlas(tuple._2());
                        }
                        catch (final Exception e)
                        {
                            throw new CoreException("Building Atlas Statistics for {} failed!",
                                    tuple._1(), e);
                        }
                        final Tuple2<String, AtlasStatistics> result = new Tuple2<>(tuple._1(),
                                statistics);
                        return result;
                    });

            // Cache the statistics
            statisticsRDD.cache();
            logger.info("\n\n********** CACHED THE SHARD STATISTICS **********\n");

            // Save the metrics
            // splitAndSaveAsHadoopFile(statisticsRDD,
            // getAlternateParallelFolderOutput(output, SHARD_STATISTICS_FOLDER),
            // AtlasStatistics.class, MultipleAtlasStatisticsOutputFormat.class,
            // new CountrySplitter());
            statisticsRDD.saveAsHadoopFile(
                    getAlternateSubFolderOutput(output, SHARD_STATISTICS_FOLDER), Text.class,
                    AtlasStatistics.class, MultipleAtlasStatisticsOutputFormat.class,
                    new JobConf(configuration()));
            logger.info("\n\n********** SAVED THE SHARD STATISTICS **********\n");

            // Aggregate the metrics
            final JavaPairRDD<String, AtlasStatistics> reducedStatisticsRDD = statisticsRDD
                    .mapToPair(tuple ->
                    {
                        final String countryShardName = tuple._1();
                        final String countryName = StringList.split(countryShardName, "_").get(0);
                        return new Tuple2<>(countryName, tuple._2());
                    }).reduceByKey(AtlasStatistics::merge);

            // Save the aggregated metrics
            // reducedStatisticsRDD.saveAsHadoopFile(
            // getAlternateSubFolderOutput(output, COUNTRY_STATISTICS_FOLDER), Text.class,
            // AtlasStatistics.class, MultipleAtlasStatisticsOutputFormat.class,
            // new JobConf(configuration()));
            reducedStatisticsRDD.saveAsHadoopFile(
                    getAlternateSubFolderOutput(output, COUNTRY_STATISTICS_FOLDER), Text.class,
                    AtlasStatistics.class, MultipleAtlasCountryStatisticsOutputFormat.class,
                    new JobConf(configuration()));
            logger.info("\n\n********** SAVED THE COUNTRY STATISTICS **********\n");

            // Compute the deltas if needed
            if (!previousOutputForDelta.isEmpty())
            {
                // Compute the deltas
                final JavaPairRDD<String, AtlasDelta> deltasRDD = countryNonNullAtlasShardsRDD
                        .flatMapToPair(tuple ->
                        {
                            final String countryShardName = tuple._1();
                            final Atlas current = tuple._2();
                            final List<Tuple2<String, AtlasDelta>> result = new ArrayList<>();
                            try
                            {
                                final Optional<Atlas> alter = new AtlasLocator(sparkContext)
                                        .atlasForShard(previousOutputForDelta + "/"
                                                + StringList
                                                        .split(countryShardName,
                                                                CountryShard.COUNTRY_SHARD_SEPARATOR)
                                                        .get(0),
                                                countryShardName);
                                if (alter.isPresent())
                                {
                                    logger.info(
                                            "Printing memory after other Atlas loaded for Delta {}",
                                            countryShardName);
                                    Memory.printCurrentMemory();
                                    final AtlasDelta delta = new AtlasDelta(current, alter.get())
                                            .generate();
                                    result.add(new Tuple2<>(countryShardName, delta));
                                }
                            }
                            catch (final Exception e)
                            {
                                logger.error("Skipping! Could not generate deltas for {}",
                                        countryShardName, e);
                            }
                            return result;
                        });

                // deltasRDD.cache();
                // logger.info("\n\n********** CACHED THE DELTAS **********\n");

                // Save the deltas
                // splitAndSaveAsHadoopFile(deltasRDD, getAlternateOutput(output,
                // SHARD_DELTAS_FOLDER),
                // AtlasDelta.class, MultipleAtlasDeltaOutputFormat.class,
                // COUNTRY_NAME_FROM_COUNTRY_SHARD);
                // deltasRDD.saveAsHadoopFile(getAlternateOutput(output, SHARD_DELTAS_FOLDER),
                // Text.class,
                // AtlasDelta.class, MultipleAtlasDeltaOutputFormat.class,
                // new JobConf(configuration()));
                // logger.info("\n\n********** SAVED THE DELTAS **********\n");
                //
                // splitAndSaveAsHadoopFile(deltasRDD,
                // getAlternateOutput(output, SHARD_DELTAS_ADDED_FOLDER), AtlasDelta.class,
                // AddedMultipleAtlasDeltaOutputFormat.class, COUNTRY_NAME_FROM_COUNTRY_SHARD);
                // deltasRDD.saveAsHadoopFile(getAlternateOutput(output, SHARD_DELTAS_ADDED_FOLDER),
                // Text.class, AtlasDelta.class, AddedMultipleAtlasDeltaOutputFormat.class,
                // new JobConf(configuration()));
                // logger.info("\n\n********** SAVED THE DELTAS ADDED **********\n");
                //
                // splitAndSaveAsHadoopFile(deltasRDD,
                // getAlternateOutput(output, SHARD_DELTAS_CHANGED_FOLDER), AtlasDelta.class,
                // ChangedMultipleAtlasDeltaOutputFormat.class, COUNTRY_NAME_FROM_COUNTRY_SHARD);
                // deltasRDD.saveAsHadoopFile(getAlternateOutput(output,
                // SHARD_DELTAS_CHANGED_FOLDER),
                // Text.class, AtlasDelta.class, ChangedMultipleAtlasDeltaOutputFormat.class,
                // new JobConf(configuration()));
                // logger.info("\n\n********** SAVED THE DELTAS CHANGED **********\n");

                // splitAndSaveAsHadoopFile(deltasRDD,
                // getAlternateParallelFolderOutput(output, SHARD_DELTAS_REMOVED_FOLDER),
                // AtlasDelta.class, RemovedMultipleAtlasDeltaOutputFormat.class,
                // new CountrySplitter());
                deltasRDD.saveAsHadoopFile(
                        getAlternateSubFolderOutput(output, SHARD_DELTAS_REMOVED_FOLDER),
                        Text.class, AtlasDelta.class, RemovedMultipleAtlasDeltaOutputFormat.class,
                        new JobConf(configuration()));
                logger.info("\n\n********** SAVED THE DELTAS REMOVED **********\n");
            }

            // Save the result as Atlas files, one for each key.
            // splitAndSaveAsHadoopFile(countryNonNullAtlasShardsRDD,
            // getAlternateParallelFolderOutput(output, ATLAS_FOLDER), Atlas.class,
            // MultipleAtlasOutputFormat.class, new CountrySplitter());
            countryNonNullAtlasShardsRDD.saveAsHadoopFile(
                    getAlternateSubFolderOutput(output, ATLAS_FOLDER), Text.class, Atlas.class,
                    MultipleAtlasOutputFormat.class, new JobConf(configuration()));
            logger.info("\n\n********** SAVED THE ATLAS **********\n");
        }
    }

    @Override
    protected List<String> outputToClean(final CommandMap command)
    {
        final String output = output(command);
        final List<String> staticPaths = super.outputToClean(command);
        staticPaths.add(getAlternateSubFolderOutput(output, COUNTRY_STATISTICS_FOLDER));
        staticPaths.add(getAlternateSubFolderOutput(output, SHARD_STATISTICS_FOLDER));
        staticPaths.add(getAlternateSubFolderOutput(output, SHARD_DELTAS_FOLDER));
        staticPaths.add(getAlternateSubFolderOutput(output, SHARD_DELTAS_ADDED_FOLDER));
        staticPaths.add(getAlternateSubFolderOutput(output, SHARD_DELTAS_CHANGED_FOLDER));
        staticPaths.add(getAlternateSubFolderOutput(output, SHARD_DELTAS_REMOVED_FOLDER));
        staticPaths.add(getAlternateSubFolderOutput(output, ATLAS_FOLDER));
        return staticPaths;
    }

    @Override
    protected SwitchList switches()
    {
        return super.switches().with(COUNTRIES, COUNTRY_SHAPES, SHARDING_TYPE, PBF_PATH, PBF_SCHEME,
                PBF_SHARDING, PREVIOUS_OUTPUT_FOR_DELTA, CODE_VERSION, DATA_VERSION,
                EDGE_CONFIGURATION, WAY_SECTIONING_CONFIGURATION, PBF_NODE_CONFIGURATION,
                PBF_WAY_CONFIGURATION, PBF_RELATION_CONFIGURATION, USE_RAW_ATLAS);
    }

    /**
     * @param boundaries
     *            The {@link CountryBoundaryMap} to use for pbf to atlas generation
     * @param sparkContext
     *            Spark context (or configuration) as a key-value map
     * @param loadingOptions
     *            The basic required properties to create an {@link AtlasLoadingOption}
     * @param pbfContext
     *            The context explaining where to find the PBFs
     * @return a Spark {@link PairFunction} that processes an {@link AtlasGenerationTask}, loads the
     *         PBF for the task's shard, generates the raw atlas for the shard and outputs a shard
     *         name to raw atlas tuple.
     */
    private PairFunction<AtlasGenerationTask, String, Atlas> generateRawAtlas(
            final CountryBoundaryMap boundaries, final Map<String, String> sparkContext,
            final Map<String, String> loadingOptions, final PbfContext pbfContext)
    {
        return task ->
        {
            final String countryName = task.getCountry();
            final Shard shard = task.getShard();

            // Set the country code that is being processed!
            final AtlasLoadingOption atlasLoadingOption = buildAtlasLoadingOption(boundaries,
                    sparkContext, loadingOptions);
            atlasLoadingOption.setAdditionalCountryCodes(countryName);

            // Build the PbfLoader
            final PbfLoader loader = new PbfLoader(pbfContext, sparkContext, boundaries,
                    atlasLoadingOption, loadingOptions.get(CODE_VERSION.getName()),
                    loadingOptions.get(DATA_VERSION.getName()), task.getAllShards());
            final String name = countryName + CountryShard.COUNTRY_SHARD_SEPARATOR
                    + shard.getName();

            // Generate the raw Atlas for this shard
            final Atlas atlas;
            try
            {
                atlas = loader.generateRawAtlas(countryName, shard);
            }
            catch (final Throwable e)
            {
                throw new CoreException("Building raw Atlas {} failed!", name, e);
            }

            // Report on memory usage
            logger.info("Printing memory after loading raw Atlas {}", name);
            Memory.printCurrentMemory();

            // Output the Name/Atlas couple
            final Tuple2<String, Atlas> result = new Tuple2<>(name, atlas);
            return result;
        };
    }

    /**
     * @param boundaries
     *            The {@link CountryBoundaryMap} required to create an {@link AtlasLoadingOption}
     * @param sparkContext
     *            Spark context (or configuration) as a key-value map
     * @param loadingOptions
     *            The basic required properties to create an {@link AtlasLoadingOption}
     * @return a Spark {@link PairFunction} that processes a tuple of shard-name and sliced raw
     *         atlas, sections the sliced raw atlas and returns the final sectioned (and sliced) raw
     *         atlas for that shard name.
     */
    private PairFunction<Tuple2<String, Atlas>, String, Atlas> sectionRawAtlas(
            final CountryBoundaryMap boundaries, final Map<String, String> sparkContext,
            final Map<String, String> loadingOptions)
    {
        return tuple ->
        {
            final Atlas atlas;
            try
            {
                final AtlasLoadingOption atlasLoadingOption = buildAtlasLoadingOption(boundaries,
                        sparkContext, loadingOptions);

                // TODO - Leverage sharding and Atlas fetcher here!

                // Section the Atlas
                atlas = new WaySectionProcessor(tuple._2(), atlasLoadingOption).run();
            }
            catch (final Throwable e)
            {
                throw new CoreException("Slicing Atlas {} failed!", tuple._2().getName(), e);
            }

            // Report on memory usage
            logger.info("Printing memory after loading final Atlas {}", tuple._2().getName());
            Memory.printCurrentMemory();

            // Output the Name/Atlas couple
            final Tuple2<String, Atlas> result = new Tuple2<>(tuple._1(), atlas);
            return result;
        };
    }

    /**
     * @param boundaries
     *            The {@link CountryBoundaryMap} to use for slicing
     * @return a Spark {@link PairFunction} that processes a tuple of shard-name and raw atlas,
     *         slices the raw atlas and returns the sliced raw atlas for that shard name.
     */
    private PairFunction<Tuple2<String, Atlas>, String, Atlas> sliceRawAtlas(
            final CountryBoundaryMap boundaries)
    {
        return tuple ->
        {
            final Atlas slicedAtlas;

            // Grab the tuple contents
            final String shardName = tuple._1();
            final Atlas rawAtlas = tuple._2();

            try
            {
                // Extract the country code
                final Set<IsoCountry> isoCountries = new HashSet<>();
                final Optional<IsoCountry> countryName = IsoCountry
                        .forCountryCode(shardName.split(CountryShard.COUNTRY_SHARD_SEPARATOR)[0]);
                if (countryName.isPresent())
                {
                    isoCountries.add(countryName.get());
                }
                else
                {
                    logger.error("Unable to extract valid IsoCountry code from {}", shardName);
                }

                // Slice the Atlas
                slicedAtlas = new RawAtlasCountrySlicer(isoCountries, boundaries).slice(rawAtlas);
            }
            catch (final Throwable e)
            {
                throw new CoreException("Slicing raw Atlas {} failed!", rawAtlas.getName(), e);
            }

            // Report on memory usage
            logger.info("Printing memory after loading sliced raw Atlas {}", rawAtlas.getName());
            Memory.printCurrentMemory();

            // Output the Name/Atlas couple
            final Tuple2<String, Atlas> result = new Tuple2<>(tuple._1(), slicedAtlas);
            return result;
        };
    }
}
