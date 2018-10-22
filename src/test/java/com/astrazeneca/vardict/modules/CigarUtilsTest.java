package com.astrazeneca.vardict.modules;

import com.astrazeneca.vardict.Configuration;
import com.astrazeneca.vardict.collection.Tuple;
import com.astrazeneca.vardict.data.scopedata.GlobalReadOnlyScope;
import com.astrazeneca.vardict.variations.Variation;
import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static com.astrazeneca.vardict.collection.Tuple.tuple;

public class CigarUtilsTest {
    @AfterMethod
    public void cleanUp () {
        GlobalReadOnlyScope.clear();
    }

    @Test
    public void findOffsetTest() {
        Configuration config = new Configuration();
        config.goodq = 23;
        config.vext = 3;
        GlobalReadOnlyScope.init(config, null, null, null, "");

        int referencePosition = 1;
        int readPosition = 2;
        int cigarLength = 3;
        String querySequence = "ACGTACGT";
        String queryQuality = "<<<<<<<<";
        Map<Integer, Integer> refCoverage = new HashMap<>();
        Map<Integer, Character> reference = new HashMap<Integer, Character>() {{
            put(1, 'A');
            put(2, 'A');
        }};
        Tuple.Tuple4<Integer, String, String, Integer> result = CigarParser.findOffset(
                referencePosition,
                readPosition,
                cigarLength,
                querySequence,
                queryQuality,
                reference,
                refCoverage);
        Tuple.Tuple4<Integer, String, String, Integer> expectedResult = tuple(2, "GT", "<<", 2);
        Assert.assertEquals(result._1, expectedResult._1);
        Assert.assertEquals(result._2, expectedResult._2);
        Assert.assertEquals(result._3, expectedResult._3);
        Assert.assertEquals(result._4, expectedResult._4);
    }

    @DataProvider(name = "variationsForSub")
    public Object[][] variationsForSub() {
        return new Object[][] {
                {
                        new Variation() {{
                            varsCount = 4;
                            varsCountOnReverse = 4;
                            varsCountOnForward = 4;
                            meanPosition = 9;
                            meanQuality = 10.5;
                            meanMappingQuality = 31;
                            numberOfMismatches = 8;
                            highQualityReadsCount = 45;
                            lowQualityReadsCount = 34;
                        }},
                        true,
                        22.5
                },
                {
                        new Variation() {{
                            varsCount = 4;
                            varsCountOnReverse = 5;
                            varsCountOnForward = 3;
                            meanPosition = 9;
                            meanQuality = 10.5;
                            meanMappingQuality = 31;
                            numberOfMismatches = 8;
                            highQualityReadsCount = 45;
                            lowQualityReadsCount = 34;
                        }},
                        false,
                        22.5
                },
                {
                        new Variation() {{
                            varsCount = 4;
                            varsCountOnReverse = 5;
                            varsCountOnForward = 3;
                            meanPosition = 9;
                            meanQuality = 10.5;
                            meanMappingQuality = 31;
                            numberOfMismatches = 8;
                            highQualityReadsCount = 44;
                            lowQualityReadsCount = 35;
                        }},
                        false,
                        0.0
                }
        };
    }

    @Test(dataProvider = "variationsForSub")
    public void subCntTest(Object expectedVariationObject, boolean direction, double goodQuality) {
        Configuration config = new Configuration();
        config.goodq = goodQuality;
        GlobalReadOnlyScope.init(config, null, null, null, "");

        Variation variation = new Variation() {{
            varsCount = 5;
            varsCountOnReverse = 5;
            varsCountOnForward = 4;
            meanPosition = 12;
            meanQuality = 12.0;
            meanMappingQuality = 36;
            numberOfMismatches = 10;
            highQualityReadsCount = 45;
            lowQualityReadsCount = 35;
        }};
        CigarParser.subCnt(variation, direction, 3, 1.5, 5, 2);
        Variation expectedVariation = (Variation) expectedVariationObject;
        Assert.assertEquals(variation.varsCount, expectedVariation.varsCount);
        Assert.assertEquals(variation.varsCountOnReverse, expectedVariation.varsCountOnReverse);
        Assert.assertEquals(variation.varsCountOnForward, expectedVariation.varsCountOnForward);
        Assert.assertEquals(variation.meanPosition, expectedVariation.meanPosition);
        Assert.assertEquals(variation.meanQuality, expectedVariation.meanQuality);
        Assert.assertEquals(variation.meanMappingQuality, expectedVariation.meanMappingQuality);
        Assert.assertEquals(variation.numberOfMismatches, expectedVariation.numberOfMismatches);
        Assert.assertEquals(variation.highQualityReadsCount, expectedVariation.highQualityReadsCount);
        Assert.assertEquals(variation.lowQualityReadsCount, expectedVariation.lowQualityReadsCount);
    }

    @DataProvider(name = "variationsForAdd")
    public Object[][] variationsForAdd() {
        return new Object[][] {
                {
                        new Variation() {{
                            varsCount = 6;
                            varsCountOnReverse = 6;
                            varsCountOnForward = 4;
                            meanPosition = 15;
                            meanQuality = 13.5;
                            meanMappingQuality = 41;
                            numberOfMismatches = 12;
                            highQualityReadsCount = 45;
                            lowQualityReadsCount = 36;
                        }},
                        true,
                        22.5
                },
                {
                        new Variation() {{
                            varsCount = 6;
                            varsCountOnReverse = 5;
                            varsCountOnForward = 5;
                            meanPosition = 15;
                            meanQuality = 13.5;
                            meanMappingQuality = 41;
                            numberOfMismatches = 12;
                            highQualityReadsCount = 45;
                            lowQualityReadsCount = 36;
                        }},
                        false,
                        22.5
                },
                {
                        new Variation() {{
                            varsCount = 6;
                            varsCountOnReverse = 5;
                            varsCountOnForward = 5;
                            meanPosition = 15;
                            meanQuality = 13.5;
                            meanMappingQuality = 41;
                            numberOfMismatches = 12;
                            highQualityReadsCount = 46;
                            lowQualityReadsCount = 35;
                        }},
                        false,
                        0.0
                }
        };
    }

    @Test(dataProvider = "variationsForAdd")
    public void addCntTest(Object expectedVariationObject, boolean direction, double goodQuality) {
        Configuration config = new Configuration();
        config.goodq = goodQuality;
        GlobalReadOnlyScope.init(config, null, null, null, "");

        Variation variation = new Variation() {{
            varsCount = 5;
            varsCountOnReverse = 5;
            varsCountOnForward = 4;
            meanPosition = 12;
            meanQuality = 12.0;
            meanMappingQuality = 36;
            numberOfMismatches = 10;
            highQualityReadsCount = 45;
            lowQualityReadsCount = 35;
        }};
        CigarParser.addCnt(variation, direction, 3, 1.5, 5, 2);
        Variation expectedVariation = (Variation) expectedVariationObject;
        Assert.assertEquals(variation.varsCount, expectedVariation.varsCount);
        Assert.assertEquals(variation.varsCountOnReverse, expectedVariation.varsCountOnReverse);
        Assert.assertEquals(variation.varsCountOnForward, expectedVariation.varsCountOnForward);
        Assert.assertEquals(variation.meanPosition, expectedVariation.meanPosition);
        Assert.assertEquals(variation.meanQuality, expectedVariation.meanQuality);
        Assert.assertEquals(variation.meanMappingQuality, expectedVariation.meanMappingQuality);
        Assert.assertEquals(variation.numberOfMismatches, expectedVariation.numberOfMismatches);
        Assert.assertEquals(variation.highQualityReadsCount, expectedVariation.highQualityReadsCount);
        Assert.assertEquals(variation.lowQualityReadsCount, expectedVariation.lowQualityReadsCount);
    }

    @DataProvider(name = "cigar")
    public Object[][] cigar() {
        return new Object[][] {
                {
                        new Cigar(new ArrayList<CigarElement>() {{
                            add(new CigarElement(1, CigarOperator.M));
                            add(new CigarElement(2, CigarOperator.S));
                            add(new CigarElement(4, CigarOperator.I));
                            add(new CigarElement(8, CigarOperator.D));
                            add(new CigarElement(16, CigarOperator.N));
                            add(new CigarElement(32, CigarOperator.H));
                        }})
                }
        };
    }

    @Test(dataProvider = "cigar")
    public void getInsertionDeletionLengthTest(Object cigar) {
        Assert.assertEquals(CigarParser.getInsertionDeletionLength((Cigar) cigar), 12);
    }

    @Test(dataProvider = "cigar")
    public void getMatchInsertionLengthTest(Object cigar) {
        Assert.assertEquals(CigarParser.getMatchInsertionLength((Cigar) cigar), 5);
    }

    @Test(dataProvider = "cigar")
    public void getSoftClippedLengthTest(Object cigar) {
        Assert.assertEquals(CigarParser.getSoftClippedLength((Cigar) cigar), 7);
    }

    @Test(dataProvider = "cigar")
    public void getCigarOperatorTest(Object cigarObject) {
        Cigar cigar = (Cigar) cigarObject;
        CigarOperator[] operators = new CigarOperator[] {
                CigarOperator.M,
                CigarOperator.S,
                CigarOperator.I,
                CigarOperator.D,
                CigarOperator.N,
                CigarOperator.H,
        };
        for (int i = 0; i < cigar.numCigarElements(); i++) {
            Assert.assertEquals(CigarParser.getCigarOperator(cigar, i), operators[i]);
        }
    }

    @DataProvider(name = "dataForIsBEGIN_ATGC_AMP_ATGCs_END_Test")
    public Object[][] dataForIsBEGIN_ATGC_AMP_ATGCs_END_Test() {
        return new Object[][] {
                {"A&ACGT", true},
                {"A&", false},
                {"A&ASGT", false}
        };
    }

    @Test(dataProvider = "dataForIsBEGIN_ATGC_AMP_ATGCs_END_Test")
    public void isBEGIN_ATGC_AMP_ATGCs_END(final String s, final boolean result) {
        Assert.assertEquals(CigarParser.isBEGIN_ATGC_AMP_ATGCs_END(s), result);
    }
}