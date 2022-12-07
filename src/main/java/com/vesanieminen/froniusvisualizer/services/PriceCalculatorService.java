package com.vesanieminen.froniusvisualizer.services;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.vesanieminen.froniusvisualizer.services.PakastinSpotService.mapToResponse;
import static com.vesanieminen.froniusvisualizer.services.PakastinSpotService.pakastin2YearFile;
import static com.vesanieminen.froniusvisualizer.util.Utils.divide;
import static com.vesanieminen.froniusvisualizer.util.Utils.fiZoneID;
import static com.vesanieminen.froniusvisualizer.util.Utils.getCurrentTimeWithHourPrecision;
import static com.vesanieminen.froniusvisualizer.util.Utils.getVAT;
import static com.vesanieminen.froniusvisualizer.util.Utils.numberFormat;
import static com.vesanieminen.froniusvisualizer.util.Utils.sizeOf;
import static com.vesanieminen.froniusvisualizer.util.Utils.sum;

@Slf4j
public class PriceCalculatorService {

    public static final String spotPriceDataFile = "src/main/resources/data/sahko.tk/chart-alv0.csv";

    public static final DateTimeFormatter datetimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static LinkedHashMap<Instant, Double> spotPriceMap;
    public static Instant spotDataStart;
    public static Instant spotDataEnd;
    private static Double spotAverageThisYear;
    private static Double spotAverageThisMonth;

    // Old sahko.tk export file reading - requires manual work
    public static void getSpotDataSahkoTK() throws IOException {
        var map = new LinkedHashMap<>();
        final var reader = Files.newBufferedReader(Path.of(spotPriceDataFile));
        final var csvReader = new CSVReader(reader);
        csvReader.readNext(); // skip header
        String[] line;
        while ((line = csvReader.readNext()) != null) {
            final var dateTime = LocalDateTime.parse(line[0], datetimeFormatter);
            map.put(dateTime, Double.valueOf(line[1]));
        }
        log.info("size of Sahko.tk map: " + sizeOf(map));
    }

    public static LinkedHashMap<Instant, Double> getSpotData() {
        if (spotPriceMap == null) {
            spotPriceMap = updateSpotData();
        }
        return spotPriceMap;
    }

    public static LinkedHashMap<Instant, Double> updateSpotData() {
        spotPriceMap = new LinkedHashMap<>();
        final String file;
        try {
            file = Files.readString(Path.of(pakastin2YearFile));
        } catch (IOException e) {
            log.error("Could not load the spot price file", e);
            throw new RuntimeException(e);
        }
        final var pakastinResponse = mapToResponse(file);
        pakastinResponse.prices.forEach(price -> spotPriceMap.put(price.date, price.value / 10));
        spotDataStart = pakastinResponse.prices.get(0).date;
        spotDataEnd = pakastinResponse.prices.get(pakastinResponse.prices.size() - 1).date;
        log.info("updated spot data");
        //log.info("size of pakastin map: " + sizeOf(spotPriceMap));
        return spotPriceMap;
    }

    public static FingridUsageData getFingridUsageData(String filePath) throws IOException, ParseException {
        var start = Instant.MAX;
        var end = Instant.MIN;
        final var map = new LinkedHashMap<Instant, Double>();
        final var reader = Files.newBufferedReader(Path.of(filePath));
        CSVParser parser = new CSVParserBuilder().withSeparator(';').build();
        CSVReader csvReader = new CSVReaderBuilder(reader)
                .withSkipLines(1)
                .withCSVParser(parser)
                .build();
        String[] line;
        while ((line = csvReader.readNext()) != null) {
            // in case the Fingrid csv data has rows that contain: "null;MISSING", skip them
            if ("MISSING".equals(line[6])) {
                break;
            }
            final var instant = Instant.parse(line[4]);
            map.put(instant, numberFormat.parse(line[5]).doubleValue());
            if (start.isAfter(instant)) {
                start = instant;
            }
            if (end.isBefore(instant)) {
                end = instant;
            }
        }
        return new FingridUsageData(map, start, end);
    }

    public record FingridUsageData(LinkedHashMap<Instant, Double> data, Instant start, Instant end) {
    }

    public static double calculateSpotAveragePrice(LinkedHashMap<LocalDateTime, Double> spotData) {
        return spotData.values().stream().reduce(0d, Double::sum) / spotData.values().size();
    }

    public static double calculateSpotAveragePriceThisYear() {
        if (spotAverageThisYear == null) {
            final var year = getCurrentTimeWithHourPrecision().getYear();
            spotAverageThisYear = getSpotData().entrySet().stream().filter(yearFilter(year)).map(item -> item.getValue() * getVAT(item.getKey())).reduce(0d, Double::sum) / getSpotData().entrySet().stream().filter(yearFilter(year)).count();
        }
        return spotAverageThisYear;
    }

    private static Predicate<Map.Entry<Instant, Double>> yearFilter(int year) {
        return item -> item.getKey().atZone(fiZoneID).getYear() == year;
    }

    public static double calculateSpotAveragePriceThisMonth() {
        final var now = getCurrentTimeWithHourPrecision();
        final var month = now.getMonthValue();
        final var year = now.getYear();
        return getSpotData().entrySet().stream().filter(monthFilter(month, year)).map(item -> item.getValue() * getVAT(item.getKey())).reduce(0d, Double::sum) / getSpotData().entrySet().stream().filter(monthFilter(month, year)).count();
    }

    private static Predicate<Map.Entry<Instant, Double>> monthFilter(int month, int year) {
        return item -> item.getKey().atZone(fiZoneID).getMonthValue() == month && item.getKey().atZone(fiZoneID).getYear() == year;
    }

    public static double calculateSpotElectricityPrice(LinkedHashMap<LocalDateTime, Double> spotData, LinkedHashMap<LocalDateTime, Double> fingridConsumptionData, double margin) {
        return fingridConsumptionData.keySet().stream().filter(spotData::containsKey).map(item -> (spotData.get(item) + margin) * fingridConsumptionData.get(item)).reduce(0d, Double::sum) / 100;
    }

    public static SpotCalculation calculateSpotElectricityPriceDetails(LinkedHashMap<Instant, Double> fingridConsumptionData, double margin, double vat) {
        final var spotData = getSpotData();
        final var spotCalculation = fingridConsumptionData.keySet().stream().filter(spotData::containsKey)
                .map(item -> new SpotCalculation(
                        spotData.get(item) * getVAT(item, vat) + margin,
                        spotData.get(item) * getVAT(item, vat),
                        (spotData.get(item) * getVAT(item, vat) + margin) * fingridConsumptionData.get(item),
                        spotData.get(item) * getVAT(item, vat) * fingridConsumptionData.get(item),
                        fingridConsumptionData.get(item),
                        item,
                        item,
                        new HourValue(item.atZone(fiZoneID).getHour(), fingridConsumptionData.get(item)),
                        new HourValue(item.atZone(fiZoneID).getHour(), (spotData.get(item) * getVAT(item, vat) + margin) * fingridConsumptionData.get(item) / 100),
                        new HourValue(item.atZone(fiZoneID).getHour(), spotData.get(item) * getVAT(item, vat))
                ))
                .reduce(new SpotCalculation(
                        0,
                        0,
                        0,
                        0,
                        0,
                        Instant.MAX,
                        Instant.MIN,
                        HourValue.Zero(),
                        HourValue.Zero(),
                        HourValue.Zero()
                ), (i1, i2) -> new SpotCalculation(
                        i1.totalSpotPrice + i2.totalSpotPrice,
                        i1.totalSpotPriceWithoutMargin + i2.totalSpotPriceWithoutMargin,
                        i1.totalCost + i2.totalCost,
                        i1.totalCostWithoutMargin + i2.totalCostWithoutMargin,
                        i1.totalConsumption + i2.totalConsumption,
                        i1.start.compareTo(i2.start) < 0 ? i1.start : i2.start,
                        i1.end.compareTo(i2.end) > 0 ? i1.end : i2.end,
                        sum(i1.consumptionHours, i2.consumptionHours),
                        sum(i1.costHours, i2.costHours),
                        sum(i1.spotAverage, i2.spotAverage)
                ));
        final var count = fingridConsumptionData.keySet().stream().filter(spotData::containsKey).count();
        spotCalculation.averagePrice = spotCalculation.totalSpotPrice / count;
        spotCalculation.averagePriceWithoutMargin = spotCalculation.totalSpotPriceWithoutMargin / count;
        spotCalculation.totalCost = spotCalculation.totalCost / 100;
        spotCalculation.totalCostWithoutMargin = spotCalculation.totalCostWithoutMargin / 100;
        divide(spotCalculation.spotAverage, count / 24.0);
        return spotCalculation;
    }

    public static SpotCalculation calculateSpotElectricityPriceDetails(LinkedHashMap<Instant, Double> fingridConsumptionData, double margin, double vat, Instant start, Instant end) throws IOException {
        final LinkedHashMap<Instant, Double> filtered = getDateTimeRange(fingridConsumptionData, start, end);
        return calculateSpotElectricityPriceDetails(filtered, margin, vat);
    }

    private static LinkedHashMap<Instant, Double> getDateTimeRange(LinkedHashMap<Instant, Double> fingridConsumptionData, Instant start, Instant end) {
        return fingridConsumptionData.entrySet().stream().filter(item ->
                (start.compareTo(item.getKey()) <= 0 && 0 <= end.compareTo(item.getKey()))
        ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> y, LinkedHashMap::new));
    }

    public static double calculateFixedElectricityPrice(LinkedHashMap<Instant, Double> fingridConsumptionData, double fixed) {
        return fingridConsumptionData.keySet().stream().map(item -> fixed * fingridConsumptionData.get(item)).reduce(0d, Double::sum) / 100;
    }

    public static double calculateFixedElectricityPrice(LinkedHashMap<Instant, Double> fingridConsumptionData, double fixed, Instant start, Instant end) {
        final LinkedHashMap<Instant, Double> filtered = getDateTimeRange(fingridConsumptionData, start, end);
        return calculateFixedElectricityPrice(filtered, fixed);
    }

    public static class SpotCalculation {
        public double totalSpotPrice;
        public double totalSpotPriceWithoutMargin;
        public double totalCost;
        public double totalCostWithoutMargin;
        public double totalConsumption;
        public double averagePrice;
        public double averagePriceWithoutMargin;
        public Instant start;
        public Instant end;
        public double[] consumptionHours = new double[24];
        public double[] costHours = new double[24];
        public double[] spotAverage = new double[24];

        public SpotCalculation(double totalSpotPrice, double totalSpotPriceWithoutMargin, double totalCost, double totalCostWithoutMargin, double totalConsumption, Instant start, Instant end) {
            this.totalSpotPrice = totalSpotPrice;
            this.totalSpotPriceWithoutMargin = totalSpotPriceWithoutMargin;
            this.totalCost = totalCost;
            this.totalCostWithoutMargin = totalCostWithoutMargin;
            this.totalConsumption = totalConsumption;
            this.start = start;
            this.end = end;
        }

        public SpotCalculation(double totalSpotPrice, double totalSpotPriceWithoutMargin, double totalCost, double totalCostWithoutMargin, double totalConsumption, Instant start, Instant end, HourValue consumption, HourValue cost, HourValue spot) {
            this(totalSpotPrice, totalSpotPriceWithoutMargin, totalCost, totalCostWithoutMargin, totalConsumption, start, end);
            consumptionHours[consumption.hour] = consumption.value;
            costHours[cost.hour] = cost.value;
            spotAverage[spot.hour] = spot.value;
        }

        public SpotCalculation(double totalSpotPrice, double totalSpotPriceWithoutMargin, double totalCost, double totalCostWithoutMargin, double totalConsumption, Instant start, Instant end, double[] consumptionHours, double[] costHours, double[] spotAverage) {
            this(totalSpotPrice, totalSpotPriceWithoutMargin, totalCost, totalCostWithoutMargin, totalConsumption, start, end);
            this.consumptionHours = consumptionHours;
            this.costHours = costHours;
            this.spotAverage = spotAverage;
        }

    }

    public static class HourValue {
        public int hour;
        public double value;

        public HourValue(int hour, double value) {
            this.hour = hour;
            this.value = value;
        }

        public static HourValue Zero() {
            return new HourValue(0, 0);
        }
    }

}
