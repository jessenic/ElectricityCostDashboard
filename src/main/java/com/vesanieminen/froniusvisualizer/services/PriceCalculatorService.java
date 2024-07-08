package com.vesanieminen.froniusvisualizer.services;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vesanieminen.froniusvisualizer.services.model.NordpoolPrice;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.vesanieminen.froniusvisualizer.services.PakastinSpotService.mapToResponse;
import static com.vesanieminen.froniusvisualizer.services.PakastinSpotService.pakastin2YearFile;
import static com.vesanieminen.froniusvisualizer.util.Utils.dayFilter;
import static com.vesanieminen.froniusvisualizer.util.Utils.divide;
import static com.vesanieminen.froniusvisualizer.util.Utils.fiZoneID;
import static com.vesanieminen.froniusvisualizer.util.Utils.getCurrentTimeWithHourPrecision;
import static com.vesanieminen.froniusvisualizer.util.Utils.getVAT;
import static com.vesanieminen.froniusvisualizer.util.Utils.isAfter;
import static com.vesanieminen.froniusvisualizer.util.Utils.isBefore;
import static com.vesanieminen.froniusvisualizer.util.Utils.isBetweenHours;
import static com.vesanieminen.froniusvisualizer.util.Utils.monthFilter;
import static com.vesanieminen.froniusvisualizer.util.Utils.nordpoolZoneID;
import static com.vesanieminen.froniusvisualizer.util.Utils.numberFormat;
import static com.vesanieminen.froniusvisualizer.util.Utils.sum;

@Slf4j
public class PriceCalculatorService {

    private static LinkedHashMap<Instant, Double> spotPriceMap;
    private static List<NordpoolPrice> nordpoolPriceList;
    public static Instant spotDataStart;
    public static Instant spotDataEnd;

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
        updateNordPoolPriceList();
        return spotPriceMap;
    }

    public static void updateNordPoolPriceList() {
        if (spotPriceMap != null) {
            nordpoolPriceList = spotPriceMap.entrySet().stream().map(item -> new NordpoolPrice(item.getValue() * getVAT(item.getKey()), item.getKey().toEpochMilli())).toList();
        }
    }

    public static FingridUsageData getFingridUsageData(String filePath) throws IOException, ParseException, CsvValidationException {
        var start = Instant.MAX;
        var end = Instant.MIN;
        final var map = new LinkedHashMap<Instant, Double>();
        final var reader = Files.newBufferedReader(Path.of(filePath));
        CSVParser parser = new CSVParserBuilder().withSeparator(';').build();
        CSVReader csvReader = new CSVReaderBuilder(reader)
                .withSkipLines(0)
                .withCSVParser(parser)
                .build();

        String[] header = csvReader.readNext();
        boolean isNewFormat = header.length == 8;

        String[] line;
        while ((line = csvReader.readNext()) != null) {
            // in case the Fingrid csv data has rows that contain: "null;MISSING", skip them
            if ("MISSING".equals(line[getColumnIndex(isNewFormat, 6)])) {
                break;
            }
            final var instant = Instant.parse(line[getColumnIndex(isNewFormat, 4)]);
            if (isNewFormat) {
                // On 2023-01-16 Fingrid changed from . to , for the comma separator
                if (line[6].contains(".")) {
                    map.put(instant, Double.parseDouble(line[6]));
                } else {
                    map.put(instant, numberFormat.parse(line[6]).doubleValue());
                }
            } else {
                map.put(instant, numberFormat.parse(line[5]).doubleValue());
            }
            if (start.isAfter(instant)) {
                start = instant;
            }
            if (end.isBefore(instant)) {
                end = instant;
            }
        }
        return new FingridUsageData(map, start, end);
    }

    public static FingridUsageData getFingridUsageData(MemoryBuffer memoryBuffer) throws IOException, ParseException, CsvValidationException {
        var start = Instant.MAX;
        var end = Instant.MIN;
        final var map = new LinkedHashMap<Instant, Double>();
        final var reader = new InputStreamReader(memoryBuffer.getInputStream());
        CSVParser parser = new CSVParserBuilder().withSeparator(';').build();
        CSVReader csvReader = new CSVReaderBuilder(reader)
                .withSkipLines(0)
                .withCSVParser(parser)
                .build();

        String[] header = csvReader.readNext();
        boolean isNewFormat = header.length == 8;

        String[] line;
        while ((line = csvReader.readNext()) != null) {
            // in case the Fingrid csv data has rows that contain: "null;MISSING", skip them
            if ("MISSING".equals(line[getColumnIndex(isNewFormat, 6)])) {
                break;
            }
            final var instant = Instant.parse(line[getColumnIndex(isNewFormat, 4)]);
            if (isNewFormat) {
                // On 2023-01-16 Fingrid changed from . to , for the comma separator
                if (line[6].contains(".")) {
                    map.put(instant, Double.parseDouble(line[6]));
                } else {
                    // in case of 15min interval data, combine 4 values into 1h
                    if ("PT15M".equals(line[2])) {
                        final var _15Min = csvReader.readNext();
                        final var _30Min = csvReader.readNext();
                        final var _45Min = csvReader.readNext();
                        // skip if there are no values to combine for a full hour
                        if (_15Min == null || _30Min == null || _45Min == null) {
                            break;
                        }
                        // also skip if one of the 15min interval values is missing
                        if ("MISSING".equals(_15Min[getColumnIndex(true, 6)]) || "MISSING".equals(_30Min[getColumnIndex(isNewFormat, 6)]) || "MISSING".equals(_45Min[getColumnIndex(isNewFormat, 6)])) {
                            break;
                        }
                        final var value00Min = numberFormat.parse(line[6]).doubleValue();
                        final var value15Min = numberFormat.parse(_15Min[6]).doubleValue();
                        final var value30Min = numberFormat.parse(_30Min[6]).doubleValue();
                        final var value45Min = numberFormat.parse(_45Min[6]).doubleValue();
                        map.put(instant, value00Min + value15Min + value30Min + value45Min);
                    } else {
                        map.put(instant, numberFormat.parse(line[6]).doubleValue());
                    }
                }
            } else {
                map.put(instant, numberFormat.parse(line[5]).doubleValue());
            }
            if (start.isAfter(instant)) {
                start = instant;
            }
            if (end.isBefore(instant)) {
                end = instant;
            }
        }
        reader.close();
        return new FingridUsageData(map, start, end);
    }

    private static int getColumnIndex(boolean isNewFormat, int index) {
        return isNewFormat ? index + 1 : index;
    }

    public record FingridUsageData(LinkedHashMap<Instant, Double> data, Instant start, Instant end) {
    }

    public static double calculateSpotAveragePrice(LinkedHashMap<LocalDateTime, Double> spotData) {
        return spotData.values().stream().reduce(0d, Double::sum) / spotData.values().size();
    }

    public static double calculateSpotAveragePriceThisYear() {
        //if (spotAverageThisYear == null) {
        final var year = getCurrentTimeWithHourPrecision().getYear();
        return getSpotData().entrySet().stream().filter(yearFilter(year)).map(item -> item.getValue() * getVAT(item.getKey())).reduce(0d, Double::sum) / getSpotData().entrySet().stream().filter(yearFilter(year)).count();
        //spotAverageThisYear = getSpotData().entrySet().stream().filter(yearFilter(year)).map(item -> item.getValue() * getVAT(item.getKey())).reduce(0d, Double::sum) / getSpotData().entrySet().stream().filter(yearFilter(year)).count();
        //}
        //return spotAverageThisYear;
    }

    public static double calculateSpotAveragePriceThisYearWithoutVAT() {
        final var year = getCurrentTimeWithHourPrecision().getYear();
        return getSpotData().entrySet().stream().filter(yearFilter(year)).map(Map.Entry::getValue).reduce(0d, Double::sum) / getSpotData().entrySet().stream().filter(yearFilter(year)).count();
    }

    private static Predicate<Map.Entry<Instant, Double>> yearFilter(int year) {
        return item -> item.getKey().atZone(fiZoneID).getYear() == year;
    }

    public static double calculateSpotAveragePriceOnMonth(int year, int month) {
        return getSpotData().entrySet().stream().filter(monthFilter(month, year)).map(item -> item.getValue() * getVAT(item.getKey())).reduce(0d, Double::sum) / getSpotData().entrySet().stream().filter(monthFilter(month, year)).count();
    }

    public static double calculateSpotAveragePriceThisMonth() {
        final var now = getCurrentTimeWithHourPrecision();
        final var month = now.getMonthValue();
        final var year = now.getYear();
        return getSpotData().entrySet().stream().filter(monthFilter(month, year)).map(item -> item.getValue() * getVAT(item.getKey())).reduce(0d, Double::sum) / getSpotData().entrySet().stream().filter(monthFilter(month, year)).count();
    }

    public static double calculateSpotAveragePriceThisMonthWithoutVAT() {
        final var now = getCurrentTimeWithHourPrecision();
        final var month = now.getMonthValue();
        final var year = now.getYear();
        return getSpotData().entrySet().stream().filter(monthFilter(month, year)).map(Map.Entry::getValue).reduce(0d, Double::sum) / getSpotData().entrySet().stream().filter(monthFilter(month, year)).count();
    }

    public static double calculateSpotAveragePriceToday() {
        final var now = getCurrentTimeWithHourPrecision();
        final var day = now.getDayOfMonth();
        final var month = now.getMonthValue();
        final var year = now.getYear();
        return getSpotData().entrySet().stream().filter(dayFilter(day, month, year)).map(item -> item.getValue() * getVAT(item.getKey())).reduce(0d, Double::sum) / getSpotData().entrySet().stream().filter(dayFilter(day, month, year)).count();
    }

    public static double calculateSpotAveragePriceTodayWithoutVAT() {
        final var now = getCurrentTimeWithHourPrecision();
        final var day = now.getDayOfMonth();
        final var month = now.getMonthValue();
        final var year = now.getYear();
        return getSpotData().entrySet().stream().filter(dayFilter(day, month, year)).map(Map.Entry::getValue).reduce(0d, Double::sum) / getSpotData().entrySet().stream().filter(dayFilter(day, month, year)).count();
    }

    public static List<Double> getPricesToday() {
        final var now = getCurrentTimeWithHourPrecision();
        final var day = now.getDayOfMonth();
        final var month = now.getMonthValue();
        final var year = now.getYear();
        return getSpotData().entrySet().stream().filter(dayFilter(day, month, year)).map(item -> item.getValue() * getVAT(item.getKey())).collect(Collectors.toList());
    }

    public static List<Double> getPricesTomorrow() {
        final var now = getCurrentTimeWithHourPrecision();
        final var day = now.getDayOfMonth() + 1;
        final var month = now.getMonthValue();
        final var year = now.getYear();
        return getSpotData().entrySet().stream().filter(dayFilter(day, month, year)).map(item -> item.getValue() * getVAT(item.getKey())).collect(Collectors.toList());
    }

    public static List<Map.Entry<Instant, Double>> getPriceDataToday() {
        final var now = getCurrentTimeWithHourPrecision();
        final var day = now.getDayOfMonth();
        final var month = now.getMonthValue();
        final var year = now.getYear();
        return getSpotData().entrySet().stream().filter(dayFilter(day, month, year)).map(item -> Map.entry(item.getKey(), item.getValue() * getVAT(item.getKey()))).collect(Collectors.toList());
    }

    public static Set<Map.Entry<Instant, Double>> getPriceDataForMonth() {
        final var now = getCurrentTimeWithHourPrecision();
        final var month = now.getMonthValue();
        final var year = now.getYear();
        return getSpotData().entrySet().stream().filter(monthFilter(month, year)).map(item -> Map.entry(item.getKey(), item.getValue() * getVAT(item.getKey()))).collect(Collectors.toSet());
    }

    public static List<Double> getPricesForYear() {
        final var now = getCurrentTimeWithHourPrecision();
        final var year = now.getYear();
        return getSpotData().entrySet().stream().filter(yearFilter(year)).map(item -> item.getValue() * getVAT(item.getKey())).collect(Collectors.toList());
    }

    public static List<NordpoolPrice> getPrices() {
        return nordpoolPriceList;
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
                        new HourValue(item.atZone(fiZoneID).getHour(), (spotData.get(item) * getVAT(item, vat)) * fingridConsumptionData.get(item) / 100),
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
                        sum(i1.costHoursWithoutMargin, i2.costHoursWithoutMargin),
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
        if (fingridConsumptionData == null)
            return null;
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

    private static LinkedHashMap<Instant, Double> getDateTimeRangeWithFilter(LinkedHashMap<Instant, Double> fingridConsumptionData, Instant start, Instant end, int hourAfter, int hourBefore) {
        return getDateTimeRange(fingridConsumptionData, start, end).entrySet().stream().filter(isBetweenHours(hourAfter, hourBefore))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> y, LinkedHashMap::new));
    }

    public static double calculateDayPrice(LinkedHashMap<Instant, Double> fingridConsumptionData, double price, Instant start, Instant end) {
        final LinkedHashMap<Instant, Double> filtered = getDateTimeRangeWithFilter(fingridConsumptionData, start, end, 7, 22);
        return calculateFixedElectricityPrice(filtered, price);
    }

    public static double calculateConsumption(LinkedHashMap<Instant, Double> fingridConsumptionData) {
        return fingridConsumptionData.values().stream().reduce(0d, Double::sum);
    }

    public static double calculateDayConsumption(LinkedHashMap<Instant, Double> fingridConsumptionData, Instant start, Instant end) {
        final LinkedHashMap<Instant, Double> filtered = getDateTimeRangeWithFilter(fingridConsumptionData, start, end, 7, 22);
        return calculateConsumption(filtered);
    }

    public static double calculateNightConsumption(LinkedHashMap<Instant, Double> fingridConsumptionData, Instant start, Instant end) {
        final LinkedHashMap<Instant, Double> filtered = getDateTimeRangeNightFilter(fingridConsumptionData, start, end, 22, 7);
        return calculateConsumption(filtered);
    }

    private static LinkedHashMap<Instant, Double> getDateTimeRangeNightFilter(LinkedHashMap<Instant, Double> fingridConsumptionData, Instant start, Instant end, int hourAfter, int hourBefore) {
        return getDateTimeRange(fingridConsumptionData, start, end).entrySet().stream().filter(isBefore(hourBefore).or(isAfter(hourAfter)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> y, LinkedHashMap::new));
    }

    public static double calculateNightPrice(LinkedHashMap<Instant, Double> fingridConsumptionData, double price, Instant start, Instant end) {
        final LinkedHashMap<Instant, Double> filtered = getDateTimeRangeNightFilter(fingridConsumptionData, start, end, 22, 7);
        return calculateFixedElectricityPrice(filtered, price);
    }

    public static ArrayList<Double> calculateFixedElectricityPriceWithPastProductionReduced(LinkedHashMap<Instant, Double> fingridConsumptionData, LinkedHashMap<Instant, Double> fingridProductionData, double fixed, Instant start, Instant end) {
        final LinkedHashMap<Instant, Double> filteredConsumption = getDateTimeRange(fingridConsumptionData, start, end);
        final LinkedHashMap<Instant, Double> filteredProduction = getDateTimeRange(fingridProductionData, start, end);
        
        var energyCost = 0.0;
        var savedProduction = 0.0;
        var excessProduction = 0.0;
        for (var item : filteredConsumption.keySet()) {
            var cost = fixed * filteredConsumption.get(item) / 100;
            energyCost += cost;
            if (filteredProduction != null) {
                var production = fixed * filteredProduction.get(item) / 100;
                excessProduction += production;
    
                if (excessProduction > 0 && cost > 0) {
                    var saved = Math.min(excessProduction, cost);
                    savedProduction += saved;
                    excessProduction -= saved;
                }
            }
        }

        var retVal = new ArrayList<Double>();

        retVal.add(energyCost);
        retVal.add(savedProduction);
        retVal.add(excessProduction);

        return retVal;
    }


    public static int getLatestDayOfMonth() {
        return getPrices().get(getPrices().size() - 1).timeInstant().atZone(fiZoneID).getDayOfMonth();
    }

    /**
     * Groups together different calculation values.
     * Has multiple arrays that acts as buckets that are filled of data from different days for periods of 24h.
     */
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
        public double[] costHoursWithoutMargin = new double[24];
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

        public SpotCalculation(double totalSpotPrice, double totalSpotPriceWithoutMargin, double totalCost, double totalCostWithoutMargin, double totalConsumption, Instant start, Instant end, HourValue consumption, HourValue cost, HourValue costWithoutMargin, HourValue spot) {
            this(totalSpotPrice, totalSpotPriceWithoutMargin, totalCost, totalCostWithoutMargin, totalConsumption, start, end);
            consumptionHours[consumption.hour] = consumption.value;
            costHours[cost.hour] = cost.value;
            costHoursWithoutMargin[costWithoutMargin.hour] = costWithoutMargin.value;
            spotAverage[spot.hour] = spot.value;
        }

        public SpotCalculation(double totalSpotPrice, double totalSpotPriceWithoutMargin, double totalCost, double totalCostWithoutMargin, double totalConsumption, Instant start, Instant end, double[] consumptionHours, double[] costHours, double[] costHoursWithoutMargin, double[] spotAverage) {
            this(totalSpotPrice, totalSpotPriceWithoutMargin, totalCost, totalCostWithoutMargin, totalConsumption, start, end);
            this.consumptionHours = consumptionHours;
            this.costHours = costHours;
            this.costHoursWithoutMargin = costHoursWithoutMargin;
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

    public static boolean hasBeenUpdatedSuccessfullyToday() {
        if (spotPriceMap == null || spotDataEnd == null) {
            return false;
        }
        final var zonedDateTime = spotDataEnd.atZone(nordpoolZoneID).truncatedTo(ChronoUnit.DAYS);
        final var other = ZonedDateTime.now(nordpoolZoneID).truncatedTo(ChronoUnit.DAYS);
        return zonedDateTime.isAfter(other);
    }

    public static boolean hasBeenUpdatedSuccessfullyYesterday() {
        if (spotPriceMap == null || spotDataEnd == null) {
            return false;
        }
        return spotDataEnd.atZone(nordpoolZoneID).getDayOfMonth() == ZonedDateTime.now(nordpoolZoneID).getDayOfMonth();
    }

}
