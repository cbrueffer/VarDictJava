package com.astrazeneca.vardict.modules;

import com.astrazeneca.vardict.Configuration;
import com.astrazeneca.vardict.Utils;
import com.astrazeneca.vardict.collection.Tuple;
import com.astrazeneca.vardict.collection.VariationMap;
import com.astrazeneca.vardict.data.scopedata.RealignedVariationData;
import com.astrazeneca.vardict.data.Region;
import com.astrazeneca.vardict.data.scopedata.Scope;
import com.astrazeneca.vardict.variations.Variant;
import com.astrazeneca.vardict.variations.Variation;
import com.astrazeneca.vardict.variations.Vars;

import java.time.LocalDateTime;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.*;

import static com.astrazeneca.vardict.data.scopedata.GlobalReadOnlyScope.instance;
import static com.astrazeneca.vardict.Utils.*;
import static com.astrazeneca.vardict.collection.Tuple.tuple;
import static com.astrazeneca.vardict.data.Patterns.*;
import static com.astrazeneca.vardict.variations.VariationUtils.*;

public class ToVarsBuilder implements Module<RealignedVariationData, Tuple.Tuple2<Integer, Map<Integer, Vars>>> {
    private Region region;
    private Map<Integer, Integer> refCoverage;
    private Map<Integer, VariationMap<String, Variation>> insertionVariants;
    private Map<Integer, VariationMap<String, Variation>> nonInsertionVariants;
    private Map<Integer, Character> ref;
    private Double duprate;

    public Map<Integer, VariationMap<String, Variation>> getInsertionVariants() {
        return insertionVariants;
    }

    public Map<Integer, VariationMap<String, Variation>> getNonInsertionVariants() {
        return nonInsertionVariants;
    }

    private void initFromScope(Scope<RealignedVariationData> scope) {
        this.ref = scope.regionRef.referenceSequences;
        this.region = scope.region;
        this.refCoverage = scope.data.refCoverage;
        this.insertionVariants = scope.data.insertionVariants;
        this.nonInsertionVariants = scope.data.nonInsertionVariants;
        this.duprate = scope.data.duprate;
    }

    @Override
    public Scope<Tuple.Tuple2<Integer, Map<Integer, Vars>>> process(Scope<RealignedVariationData> scope) {
        initFromScope(scope);
        Configuration config = instance().conf;

        if (config.y) {
            System.err.printf("Current segment: %s:%d-%d \n", region.chr, region.start, region.end);
        }
        //the variant structure
        Map<Integer, Vars> alignedVariants = new HashMap<>();
        int lastPosition = 0;
        //Loop over positions
        for (Map.Entry<Integer, VariationMap<String, Variation>> entH : getNonInsertionVariants().entrySet()) {
            try {
                final int position = entH.getKey();
                lastPosition = position;
                VariationMap<String, Variation> varsAtCurPosition = entH.getValue();

                if (varsAtCurPosition.isEmpty()) {
                    continue;
                }

                //Skip if there are no structural variants on position or if the delete duplication option is on
                if (varsAtCurPosition.sv == null || instance().conf.deleteDuplicateVariants) {
                    //Skip if start position is outside region of interest
                    if (position < region.start || position > region.end) {
                        continue;
                    }
                }

                //Skip position if it has no coverage (except SVs)
                if (varsAtCurPosition.sv == null && !refCoverage.containsKey(position)) {
                    continue;
                }

                if (isTheSameVariationOnRef(config, position, varsAtCurPosition)) {
                    continue;
                }

                if (!refCoverage.containsKey(position) || refCoverage.get(position) == 0) { // ignore when there's no coverage
                    System.err.printf("Error tcov: %s %d %d %d %s\n",
                            region.chr, position, region.start, region.end, varsAtCurPosition.sv.type);
                    continue;
                }

                //total position coverage
                int totalPosCoverage = refCoverage.get(position);

                //position coverage by high-quality reads
                final int hicov = calcHicov(getInsertionVariants().get(position), varsAtCurPosition);

                //array of all variants for the position
                List<Variant> var = new ArrayList<>();
                List<String> keys = new ArrayList<>(varsAtCurPosition.keySet());
                Collections.sort(keys);

                //temporary array used for debugging
                List<String> debugLines = new ArrayList<>();

                createVariant(duprate, alignedVariants, position, varsAtCurPosition, totalPosCoverage, var, debugLines, keys, hicov);
                totalPosCoverage = createInsertion(duprate, position, totalPosCoverage, var, debugLines, hicov);
                sortVariants(var);

                double maxfreq = collectVarsAtPosition(alignedVariants, position, var);

                if (!config.doPileup && maxfreq <= config.freq && instance().ampliconBasedCalling == null) {
                    if (!config.bam.hasBam2()) {
                        alignedVariants.remove(position);
                        continue;
                    }
                }
                //if reference variant has frequency more than $FREQ, set genotype1 to reference variant
                //else if non-reference variant exists, set genotype1 to first (largest quality*coverage) variant
                //otherwise set genotype1 to reference variant

                Vars variationsAtPos = getOrPutVars(alignedVariants, position);

                collectReferenceVariants(position, totalPosCoverage, variationsAtPos, debugLines);
            } catch (Exception exception) {
                printExceptionAndContinue(exception, "position", String.valueOf(lastPosition), region);
            }
        }

        if (config.y) {
            System.err.println("TIME: Finish preparing vars:" + LocalDateTime.now());
        }

        return new Scope<>(
                scope,
                tuple(scope.data.maxReadLength, alignedVariants));
    }

    private boolean isTheSameVariationOnRef(Configuration config, int position, VariationMap<String, Variation> varsAtCurPosition) {
        Set<String> vk = new HashSet<>(varsAtCurPosition.keySet());
        if (getInsertionVariants().containsKey(position)) {
            vk.add("I");
        }
        if (vk.size() == 1 && ref.containsKey(position) && vk.contains(ref.get(position).toString())) {
            // ignore if only reference were seen and no pileup to avoid computation
            if (!config.doPileup && !config.bam.hasBam2() && instance().ampliconBasedCalling == null) {
                return true;
            }
        }
        return false;
    }

    private void collectReferenceVariants(int position, int totalPosCoverage, Vars variationsAtPos, List<String> debugLines) {
        //reference forward strand coverage
        int rfc = 0;
        //reference reverse strand coverage
        int rrc = 0;
        //description string for reference or best non-reference variant
        String genotype1 = "";

        if (variationsAtPos.referenceVariant != null && variationsAtPos.referenceVariant.frequency >= instance().conf.freq) {
            genotype1 = variationsAtPos.referenceVariant.descriptionString;
        } else if (variationsAtPos.variants.size() > 0) {
            genotype1 = variationsAtPos.variants.get(0).descriptionString;
        } else {
            genotype1 = variationsAtPos.referenceVariant.descriptionString;
        }
        if (variationsAtPos.referenceVariant != null) {
            rfc = variationsAtPos.referenceVariant.varsCountOnForward;
            rrc = variationsAtPos.referenceVariant.varsCountOnReverse;
        }

        if (genotype1.startsWith("+")) {
            Matcher mm = DUP_NUM.matcher(genotype1);
            if (mm.find()) {
                genotype1 = "+" + (Configuration.SVFLANK + toInt(mm.group(1)));
            }
            else {
                genotype1 = "+" + (genotype1.length() - 1);
            }
        }
        //description string for any other variant
        String genotype2 = "";

        if (totalPosCoverage > refCoverage.get(position) && getNonInsertionVariants().containsKey(position + 1)
                && ref.containsKey(position + 1)
                && getNonInsertionVariants().get(position + 1).containsKey(ref.get(position + 1).toString())) {
            Variation tpref = getVariationMaybe(getNonInsertionVariants(), position + 1, ref.get(position + 1));
            rfc = tpref.varsCountOnForward;
            rrc = tpref.varsCountOnReverse;
        }

        // only reference reads are observed.
        if (variationsAtPos.variants.size() > 0) { //Condition: non-reference variants are found
            //Loop over non-reference variants
            for (Variant vref : variationsAtPos.variants) {
                //vref - variant reference

                genotype2 = vref.descriptionString;
                if (genotype2.startsWith("+")) {
                    genotype2 = "+" + (genotype2.length() - 1);
                }
                //variant description string
                final String vn = vref.descriptionString;
                //length of deletion in variant (0 if no deletion)
                int dellen = 0;
                Matcher matcher = BEGIN_MINUS_NUMBER.matcher(vn);
                if (matcher.find()) {
                    dellen = toInt(matcher.group(1));
                }
                //effective position (??): p + dellen - 1 for deletion, p otherwise
                int ep = position;
                if (vn.startsWith("-")) {
                    ep = position + dellen - 1;
                }
                //reference sequence for variation (to be written to .vcf file)
                String refallele = "";
                //variant sequence (to be written to .vcf file)
                String varallele = "";

                // how many bp can a deletion be shifted to 3 prime
                //3' shift (integer) for MSI adjustment
                int shift3 = 0;
                double msi = 0;
                String msint = "";

                int sp = position;

                Tuple.Tuple3<Double, Integer, String> tupleRefVariant = Tuple.tuple(msi, shift3, msint);
                //if variant is an insertion
                if (vn.startsWith("+")) {
                    //If no '&' and '#' symbols are found in variant string
                    //These symbols are in variant if a matched sequence follows insertion
                    if (!vn.contains("&") && !vn.contains("#") && !vn.contains("<dup")) {
                        tupleRefVariant = proceedVrefIsInsertion(position, vn);
                        msi = tupleRefVariant._1;
                        shift3 = tupleRefVariant._2;
                        msint = tupleRefVariant._3;
                    }
                    //Shift position to 3' if -3 option is set
                    if (instance().conf.moveIndelsTo3) {
                        sp += shift3;
                        ep += shift3;
                    }
                    //reference allele is 1 base
                    refallele = ref.containsKey(position) ? ref.get(position).toString() : "";
                    //variant allele is reference base concatenated with insertion
                    varallele = refallele + vn.substring(1);
                    if (varallele.length() > instance().conf.SVMINLEN) {
                        ep += varallele.length();
                        varallele = "<DUP>";
                    }
                    Matcher mm = DUP_NUM.matcher(varallele);
                    if (mm.find()) {
                        int dupCount = toInt(mm.group(1));
                        ep = sp + (2 * Configuration.SVFLANK + dupCount) - 1;
                        genotype2 = "+" + (2 * Configuration.SVFLANK + dupCount);
                        varallele = "<DUP>";
                    }
                } else if (vn.startsWith("-")) { //deletion variant
                    Matcher matcherINV = INV_NUM.matcher(vn);
                    Matcher matcherStartMinusNum = BEGIN_MINUS_NUMBER_CARET.matcher(vn);

                    if (dellen < instance().conf.SVMINLEN) {
                        //variant allele is in the record
                        //remove '-' and number from beginning of variant string
                        varallele = vn.replaceFirst("^-\\d+", "");

                        tupleRefVariant = proceedVrefIsDeletion(position, vn, dellen);
                        msi = tupleRefVariant._1;
                        shift3 = tupleRefVariant._2;
                        msint = tupleRefVariant._3;

                        if (matcherINV.find()) {
                            varallele = "<INV>";
                            genotype2 = "<INV" + dellen + ">";
                        }
                    } else if (matcherStartMinusNum.find()) {
                        varallele = "<INV>";
                        genotype2 = "<INV" + dellen + ">";
                    } else {
                        varallele = "<DEL>";
                    }
                    //If no matched sequence or indel follows the variant
                    if (!vn.contains("&") && !vn.contains("#") && !vn.contains("^")) {
                        //Shift position to 3' if -3 option is set
                        if (instance().conf.moveIndelsTo3) {
                            sp += shift3;
                        }
                        //variant allele is 1 base from reference string preceding p
                        if (!varallele.equals("<DEL>")) {
                            varallele = ref.containsKey(position - 1) ? ref.get(position - 1).toString() : "";
                        }
                        //prepend same base to reference allele
                        refallele = ref.containsKey(position - 1) ? ref.get(position - 1).toString() : "";
                        sp--;
                    }
                    Matcher mm = SOME_SV_NUMBERS.matcher(vn);
                    if (mm.find()) {
                        refallele = ref.containsKey(position) ? ref.get(position).toString() : "";
                    }
                    else if (dellen < instance().conf.SVMINLEN) {
                        //append dellen bases from reference string to reference allele
                        refallele += joinRef(ref, position, position + dellen - 1);
                    }
                } else { //Not insertion/deletion variant. SNP or MNP
                    //Find MSI adjustment
                    String tseq1 = joinRef(ref, position - 30 > 1 ? position - 30 : 1, position + 1);
                    int chr0 = getOrElse(instance().chrLengths, region.chr, 0);
                    String tseq2 = joinRef(ref, position + 2, position + 70 > chr0 ? chr0 : position + 70);

                    Tuple.Tuple3<Double, Integer, String> tpl = findMSI(tseq1, tseq2, null);
                    msi = tpl._1;
                    shift3 = tpl._2;
                    msint = tpl._3;
                    //reference allele is 1 base from reference sequence
                    refallele = ref.containsKey(position) ? ref.get(position).toString() : "";
                    //variant allele is same as description string
                    varallele = vn;
                }

                Matcher mtch = AMP_ATGC.matcher(vn);
                if (mtch.find()) { //If variant is followed by matched sequence
                    //following matching sequence
                    String extra = mtch.group(1);
                    //remove '&' symbol from variant allele
                    varallele = varallele.replaceFirst("&", "");
                    //append length(extra) bases from reference sequence to reference allele and genotype1
                    String tch = joinRef(ref, ep + 1, ep + extra.length());
                    refallele += tch;
                    genotype1 += tch;

                    //Adjust position
                    ep += extra.length();

                    mtch = AMP_ATGC.matcher(varallele);
                    if (mtch.find()) {
                        String vextra = mtch.group(1);
                        varallele = varallele.replaceFirst("&", "");
                        tch = joinRef(ref, ep + 1, ep + vextra.length());
                        refallele += tch;
                        genotype1 += tch;
                        ep += vextra.length();
                    }

                    //If description string starts with '+' sign, remove it from reference and variant alleles
                    if (vn.startsWith("+")) {
                        refallele = refallele.substring(1);
                        varallele = varallele.substring(1);
                        sp++;
                    }

                    if (varallele.equals("<DEL>") && refallele.length() > 1) {
                        refallele = ref.containsKey(sp) ? ref.get(sp).toString() : "";
                        if (refCoverage.containsKey(sp - 1)) {
                            totalPosCoverage = refCoverage.get(sp - 1);
                        }
                        if (vref.positionCoverage > totalPosCoverage ){
                            totalPosCoverage = vref.positionCoverage;
                        }
                    }
                }

                //If variant is followed by short matched sequence and insertion/deletion
                mtch = HASH_GROUP_CARET_GROUP.matcher(vn);
                if (mtch.find()) {
                    //matched sequence
                    String mseq = mtch.group(1);
                    //insertion/deletion tail
                    String tail = mtch.group(2);

                    //adjust position by length of matched sequence
                    ep += mseq.length();

                    //append bases from reference sequence to reference allele
                    refallele += joinRef(ref, ep - mseq.length() + 1, ep);

                    //If tail is a deletion
                    mtch = BEGIN_DIGITS.matcher(tail);
                    if (mtch.find()) {
                        //append (deletion length) bases from reference sequence to reference allele
                        int d = toInt(mtch.group(1));
                        refallele += joinRef(ref, ep + 1, ep + d);

                        //shift position by deletion length
                        ep += d;
                    }

                    //clean special symbols from alleles
                    varallele = varallele.replaceFirst("#", "").replaceFirst("\\^(\\d+)?", "");

                    //replace '#' with 'm' and '^' with 'i' in genotypes
                    genotype1 = genotype1.replaceFirst("#", "m").replaceFirst("\\^", "i");
                    genotype2 = genotype2.replaceFirst("#", "m").replaceFirst("\\^", "i");
                }
                mtch = CARET_ATGNC.matcher(vn); // for deletion followed directly by insertion in novolign
                if (mtch.find()) {
                    //remove '^' sign from varallele
                    varallele = varallele.replaceFirst("\\^", "");

                    //replace '^' sign with 'i' in genotypes
                    genotype1 = genotype1.replaceFirst("\\^", "i");
                    genotype2 = genotype2.replaceFirst("\\^", "i");
                }

                //preceding reference sequence
                vref.leftseq = joinRef(ref, sp - 20 < 1 ? 1 : sp - 20, sp - 1); // left 20 nt
                int chr0 = getOrElse(instance().chrLengths, region.chr, 0);
                //following reference sequence
                vref.rightseq = joinRef(ref, ep + 1, ep + 20 > chr0 ? chr0 : ep + 20); // right 20 nt
                //genotype description string
                String genotype = genotype1 + "/" + genotype2;
                //remove '&' and '#' symbols from genotype string
                //replace '^' symbol with 'i' in genotype string
                genotype = genotype
                        .replace("&", "")
                        .replace("#", "")
                        .replace("^", "i");
                //convert extrafreq, freq, hifreq, msi fields to strings
                vref.extraFrequency = roundHalfEven("0.0000", vref.extraFrequency);
                vref.frequency = roundHalfEven("0.0000", vref.frequency);
                vref.highQualityReadsFrequency = roundHalfEven("0.0000", vref.highQualityReadsFrequency);
                vref.msi = roundHalfEven("0.000", msi);
                vref.msint = msint.length();
                vref.shift3 = shift3;
                vref.startPosition = sp;
                vref.endPosition = ep;
                vref.refallele = refallele;
                vref.varallele = varallele;
                vref.genotype = genotype;
                vref.totalPosCoverage = totalPosCoverage;
                vref.refForwardCoverage = rfc;
                vref.refReverseCoverage = rrc;

                //bias is [0-2];[0-2] where first flag is for reference, second for variant
                //if reference variant is not found, first flag is 0
                if (variationsAtPos.referenceVariant != null) {
                    vref.strandBiasFlag = variationsAtPos.referenceVariant.strandBiasFlag + ";" + vref.strandBiasFlag;
                } else {
                    vref.strandBiasFlag = "0;" + vref.strandBiasFlag;
                }

                adjustVariantCounts(position, vref);

                if (instance().conf.debug) {
                    StringBuilder sb = new StringBuilder();
                    for (String str : debugLines) {
                        if (sb.length() > 0) {
                            sb.append(" & ");
                        }
                        sb.append(str);
                    }
                    vref.DEBUG = sb.toString();
                }
            }
            //TODO: It is a "lazy" solution because current logic in realignment methods can't be changed simply for --nosv option
            if (instance().conf.disableSV) {
                variationsAtPos.variants.removeIf(vref -> ANY_SV.matcher(vref.varallele).find());
            }
        } else if (variationsAtPos.referenceVariant != null) {
            Variant vref = variationsAtPos.referenceVariant; //no variant reads are detected.
            vref.totalPosCoverage = totalPosCoverage;
            vref.positionCoverage = 0;
            vref.frequency = 0;
            vref.refForwardCoverage = rfc;
            vref.refReverseCoverage = rrc;
            vref.varsCountOnForward = 0;
            vref.varsCountOnReverse = 0;
            vref.msi = 0;
            vref.msint = 0;
            vref.strandBiasFlag += ";0";
            vref.shift3 = 0;
            vref.startPosition = position;
            vref.endPosition = position;
            vref.highQualityReadsFrequency = roundHalfEven("0.0000", vref.highQualityReadsFrequency);
            String r = ref.containsKey(position) ? ref.get(position).toString() : "";
            //both refallele and varallele are 1 base from reference string
            vref.refallele = r;
            vref.varallele = r;
            vref.genotype = r + "/" + r;
            vref.leftseq = "";
            vref.rightseq = "";
            vref.duprate = duprate;
            if (instance().conf.debug) {
                StringBuilder sb = new StringBuilder();
                for (String str : debugLines) {
                    if (sb.length() > 0) {
                        sb.append(" & ");
                    }
                    sb.append(str);
                }
                vref.DEBUG = sb.toString();
            }
        } else {
            variationsAtPos.referenceVariant = new Variant();
        }
    }

    private Tuple.Tuple3<Double,Integer,String> proceedVrefIsDeletion(int position, String vn, int dellen) {
        //left 70 bases in reference sequence
        String leftseq = joinRef(ref, (position - 70 > 1 ? position - 70 : 1), position - 1); // left 10 nt
        int chr0 = getOrElse(instance().chrLengths, region.chr, 0);
        //right 70 + dellen bases in reference sequence
        String tseq = joinRef(ref, position, position + dellen + 70 > chr0 ? chr0 : position + dellen + 70);

        //Try to adjust for microsatellite instability
        Tuple.Tuple3<Double, Integer, String> tpl = findMSI(substr(tseq, 0, dellen),
                substr(tseq, dellen), leftseq);
        double msi = tpl._1;
        int shift3 = tpl._2;
        String msint = tpl._3;

        tpl = findMSI(leftseq, substr(tseq, dellen), null);
        double tmsi = tpl._1;
        String tmsint = tpl._3;
        if (msi < tmsi) {
            msi = tmsi;
            // Don't change shift3
            msint = tmsint;
        }
        if (msi <= shift3 / (double) dellen) {
            msi = shift3 / (double) dellen;
        }
        return tuple(msi, shift3, msint);
    }

    private Tuple.Tuple3<Double,Integer,String> proceedVrefIsInsertion(int position, String vn) {
        //variant description string without first symbol '+'
        String tseq1 = vn.substring(1);
        //left 50 bases in reference sequence
        String leftseq = joinRef(ref, position - 50 > 1 ? position - 50 : 1, position); // left 10 nt
        int x = getOrElse(instance().chrLengths, region.chr, 0);
        //right 70 bases in reference sequence
        String tseq2 = joinRef(ref, position + 1, (position + 70 > x ? x : position + 70));

        Tuple.Tuple3<Double, Integer, String> tpl = findMSI(tseq1, tseq2, leftseq);
        double msi = tpl._1;
        int shift3 = tpl._2;
        String msint = tpl._3;

        //Try to adjust for microsatellite instability
        tpl = findMSI(leftseq, tseq2, null);
        double tmsi = tpl._1;
        String tmsint = tpl._3;
        if (msi < tmsi) {
            msi = tmsi;
            // Don't change shift3
            msint = tmsint;
        }
        if (msi <= shift3 / (double)tseq1.length()) {
            msi = shift3 / (double)tseq1.length();
        }
        return tuple(msi, shift3, msint);
    }

    private double collectVarsAtPosition(Map<Integer, Vars> alignedVariants, int position, List<Variant> var) {
        double maxfreq = 0;
        for (Variant tvar : var) {
            //If variant description string is 1-char base and it matches reference base at this position
            if (tvar.descriptionString.equals(String.valueOf(ref.get(position)))) {
                //this is a reference variant
                getOrPutVars(alignedVariants, position).referenceVariant = tvar;
            } else {
                //append variant to VAR and put it to VARN with key tvar.n (variant description string)
                getOrPutVars(alignedVariants, position).variants.add(tvar);
                getOrPutVars(alignedVariants, position).varDescriptionStringToVariants.put(tvar.descriptionString, tvar);
                if (tvar.frequency > maxfreq) {
                    maxfreq = tvar.frequency;
                }
            }
        }
        return maxfreq;
    }

    private void sortVariants(List<Variant> var) {
        //sort variants by product of quality and coverage
        Collections.sort(var, new Comparator<Variant>() {
            @Override
            public int compare(Variant o1, Variant o2) {
                return Double.compare(o2.meanQuality * o2.positionCoverage, o1.meanQuality * o1.positionCoverage);
            }
        });
    }

    int createInsertion(double duprate, int position, int totalPosCoverage,
                        List<Variant> var, List<String> debugLines, int hicov) {
        //Handle insertions separately
        Map<String, Variation> iv = getInsertionVariants().get(position);
        if (iv != null) {
            List<String> ikeys = new ArrayList<>(iv.keySet());
            Collections.sort(ikeys);
            //Loop over insertion variants
            for (String n : ikeys) {
                // String n = entV.getKey();
                Variation cnt = iv.get(n);
                //count of variants in forward strand
                int fwd = cnt.getDir(false);
                //count of variants in reverse strand
                int rev = cnt.getDir(true);
                //strand bias flag (0, 1 or 2)
                int bias = strandBias(fwd, rev);
                //mean base quality for variant
                double vqual = roundHalfEven("0.0", cnt.meanQuality / cnt.varsCount); // base quality
                //mean mapping quality for variant
                double mq = roundHalfEven("0.0", cnt.meanMappingQuality / (double)cnt.varsCount); // mapping quality
                //number of high-quality reads for variant
                int hicnt = cnt.highQualityReadsCount;
                //number of low-quality reads for variant
                int locnt = cnt.lowQualityReadsCount;

                // Also commented in Perl
                // hicov += hicnt;

                //adjust position coverage if variant count is more than position coverage and no more than
                // position coverage + extracnt
                int ttcov = totalPosCoverage;
                if (cnt.varsCount > totalPosCoverage && cnt.extracnt != 0 &&cnt.varsCount - totalPosCoverage < cnt.extracnt) {
                    ttcov = cnt.varsCount;
                }

                if (ttcov < cnt.varsCount) {
                    ttcov = cnt.varsCount;
                    if (refCoverage.containsKey(position + 1) && ttcov < refCoverage.get(position + 1) - cnt.varsCount) {
                        ttcov = refCoverage.get(position + 1);
                        // Adjust the reference
                        Variation variantNextPosition = getVariationMaybe(getNonInsertionVariants(), position + 1, ref.get(position + 1));
                        if (variantNextPosition != null) {
                            variantNextPosition.varsCountOnForward -= fwd;
                            variantNextPosition.varsCountOnReverse -= rev;
                        }
                    }
                    totalPosCoverage = ttcov;
                }

                Variant tvref = new Variant();
                tvref.descriptionString = n;
                tvref.positionCoverage = cnt.varsCount;
                tvref.varsCountOnForward = fwd;
                tvref.varsCountOnReverse = rev;
                tvref.strandBiasFlag = String.valueOf(bias);
                tvref.frequency = Utils.roundHalfEven("0.0000", cnt.varsCount / (double) ttcov);
                tvref.meanPosition = Utils.roundHalfEven("0.0", cnt.meanPosition / (double) cnt.varsCount);
                tvref.isAtLeastAt2Positions = cnt.pstd;
                tvref.meanQuality = vqual;
                tvref.hasAtLeast2DiffQualities = cnt.qstd;
                tvref.meanMappingQuality = mq;
                tvref.highQualityToLowQualityRatio = hicnt / (locnt != 0 ? locnt : 0.5d);
                tvref.highQualityReadsFrequency = hicov > 0 ? hicnt / (double)hicov : 0;
                tvref.extraFrequency = cnt.extracnt != 0 ? cnt.extracnt / (double)ttcov : 0;
                tvref.shift3 = 0;
                tvref.msi = 0;
                tvref.numberOfMismatches = Utils.roundHalfEven("0.0", cnt.numberOfMismatches / (double)cnt.varsCount);
                tvref.hicnt = hicnt;
                tvref.hicov = hicov;
                tvref.duprate = duprate;

                var.add(tvref);
                if (instance().conf.debug) {
                    tvref.debugVariantsContentInsertion(debugLines, n);
                }
            }
        }
        return totalPosCoverage;
    }

    void createVariant(double duprate, Map<Integer, Vars> vars, int position, VariationMap<String, Variation> v,
                                      int tcov, List<Variant> var, List<String> debugLines, List<String> keys, int hicov) {
        //Loop over all variants found for the position except insertions
        for (String n : keys) {
            if (n.equals("SV")) {
                VariationMap.SV sv = v.sv;
                getOrPutVars(vars, position).sv = sv.splits + "-" + sv.pairs + "-" + sv.clusters;
                continue;
            }
            Variation cnt = v.get(n);
            if (cnt.varsCount == 0) { //Skip variant if it does not have count
                continue;
            }
            //count of variants in forward strand
            int fwd = cnt.getDir(false);
            //count of variants in reverse strand
            int rev = cnt.getDir(true);
            //strand bias flag (0, 1 or 2)
            int bias = strandBias(fwd, rev);
            //mean base quality for variant
            double vqual = roundHalfEven("0.0", cnt.meanQuality / cnt.varsCount); // base quality
            //mean mapping quality for variant
            double mq = roundHalfEven("0.0", cnt.meanMappingQuality / (double) cnt.varsCount);
            //number of high-quality reads for variant
            int hicnt = cnt.highQualityReadsCount;
            //number of low-quality reads for variant
            int locnt = cnt.lowQualityReadsCount;
            /**
             * Condition:
             # 1). cnt.cnt > tcov                         - variant count is more than position coverage
             # 2). cnt.cnt - tcov < cnt.extracnt          - variant count is no more than position coverage + extracnt
             */
            int ttcov = tcov;
            if (cnt.varsCount > tcov && cnt.extracnt > 0 && cnt.varsCount - tcov < cnt.extracnt) { //adjust position coverage if condition holds
                ttcov = cnt.varsCount;
            }

            //create variant record
            Variant tvref = new Variant();
            tvref.descriptionString = n;
            tvref.positionCoverage = cnt.varsCount;
            tvref.varsCountOnForward = fwd;
            tvref.varsCountOnReverse = rev;
            tvref.strandBiasFlag = String.valueOf(bias);
            tvref.frequency = Utils.roundHalfEven("0.0000", cnt.varsCount / (double) ttcov);
            tvref.meanPosition = Utils.roundHalfEven("0.0", cnt.meanPosition / (double) cnt.varsCount);
            tvref.isAtLeastAt2Positions = cnt.pstd;
            tvref.meanQuality = vqual;
            tvref.hasAtLeast2DiffQualities = cnt.qstd;
            tvref.meanMappingQuality = mq;
            tvref.highQualityToLowQualityRatio = hicnt / (locnt != 0 ? locnt : 0.5d);
            tvref.highQualityReadsFrequency = hicov > 0 ? hicnt / (double) hicov : 0;
            tvref.extraFrequency = cnt.extracnt != 0 ? cnt.extracnt / (double) ttcov : 0;
            tvref.shift3 = 0;
            tvref.msi = 0;
            tvref.numberOfMismatches = Utils.roundHalfEven("0.0", cnt.numberOfMismatches / (double) cnt.varsCount);
            tvref.hicnt = hicnt;
            tvref.hicov = hicov;
            tvref.duprate = duprate;

            //append variant record
            var.add(tvref);
            if (instance().conf.debug) {
                tvref.debugVariantsContentSimple(debugLines, n);
            }
        }
    }

    /**
     * Adjust variant negative counts of fields FWD, REV, RFC, RRC to zeros and print the information message to console
     * @param p start position of variant
     * @param vref variant to adjust
     */
    private void adjustVariantCounts(int p, Variant vref) {
        String message = "column in variant on position: " + p + " " + vref.refallele + "->" +
                vref.varallele + " was negative, adjusted to zero.";

        if (vref.refForwardCoverage < 0 ) {
            vref.refForwardCoverage = 0;
            System.err.println("Reference forward count " + message);
        }
        if (vref.refReverseCoverage < 0) {
            vref.refReverseCoverage = 0;
            System.err.println("Reference reverse count " + message);
        }
        if (vref.varsCountOnForward < 0) {
            vref.varsCountOnForward = 0;
            System.err.println("Variant forward count " + message);
        }
        if (vref.varsCountOnReverse < 0 ) {
            vref.varsCountOnReverse = 0;
            System.err.println("Variant reverse count " + message);
        }
    }

    private int calcHicov(VariationMap<String, Variation> iv,
                                 VariationMap<String, Variation> v) {
        int hicov = 0;
        for (Map.Entry<String, Variation> vr : v.entrySet()) {
            if (vr.getKey().equals("SV")) {
                continue;
            }
            hicov += vr.getValue().highQualityReadsCount;
        }
        if (iv != null) {
            for (Variation vr : iv.values()) {
                hicov += vr.highQualityReadsCount;
            }
        }
        return hicov;
    }

    /**
     * Find microsatellite instability
     * Tandemly repeated short sequence motifs ranging from 1– 6(8 in our case) base pairs are called microsatellites.
     * Other frequently used terms for these DNA regions are simple sequences or short tandem repeats (STRs)
     * @param tseq1 variant description string
     * @param tseq2 right 70 bases in reference sequence
     * @param left left 50 bases in reference sequence
     * @return Tuple of (MSI count, No. of bases to be shifted to 3 prime for deletions due to alternative alignment,
     * MicroSattelite unit length in base pairs)
     */
    private static Tuple.Tuple3<Double, Integer, String> findMSI(String tseq1, String tseq2, String left) {

        //Number of nucleotides in microsattelite
        int nmsi = 1;
        //Number of bases to be shifted to 3 prime
        int shift3 = 0;
        String maxmsi = "";
        double msicnt = 0;
        while (nmsi <= tseq1.length() && nmsi <= 6) {
            //Microsattelite nucleotide sequence; trim nucleotide(s) from the end
            String msint = substr(tseq1, -nmsi, nmsi);
            Pattern pattern = Pattern.compile("((" + msint + ")+)$");
            Matcher mtch = pattern.matcher(tseq1);
            String msimatch = "";
            if (mtch.find()) {
                msimatch = mtch.group(1);
            }
            if (left != null && !left.isEmpty()) {
                mtch = pattern.matcher(left + tseq1);
                if (mtch.find()) {
                    msimatch = mtch.group(1);
                }
            }
            double curmsi = msimatch.length() / (double)nmsi;
            mtch = Pattern.compile("^((" + msint + ")+)").matcher(tseq2);
            if (mtch.find()) {
                curmsi += mtch.group(1).length() / (double)nmsi;
            }
            if (curmsi > msicnt) {
                maxmsi = msint;
                msicnt = curmsi;
            }
            nmsi++;
        }

        String tseq = tseq1 + tseq2;
        while (shift3 < tseq2.length() && tseq.charAt(shift3) == tseq2.charAt(shift3)) {
            shift3++;
        }
        return tuple(msicnt, shift3, maxmsi);
    }

}
