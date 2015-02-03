package org.bbop.apollo

import grails.transaction.Transactional
import groovy.transform.CompileStatic
import org.apache.shiro.SecurityUtils
import org.bbop.apollo.filter.Cds3Filter
import org.bbop.apollo.filter.StopCodonFilter
import org.bbop.apollo.sequence.SequenceTranslationHandler
import org.bbop.apollo.sequence.TranslationTable
import org.bbop.apollo.web.util.JSONUtil
import org.codehaus.groovy.grails.web.json.JSONArray
import org.codehaus.groovy.grails.web.json.JSONException
import org.codehaus.groovy.grails.web.json.JSONObject

//import org.json.JSONObject

/**
 * taken from AbstractBioFeature
 */
//@GrailsCompileStatic
@Transactional
//@CompileStatic
class FeatureService {


    NameService nameService
    ConfigWrapperService configWrapperService
    TranscriptService transcriptService
//    CvTermService cvTermService
    ExonService exonService
    CdsService cdsService
    NonCanonicalSplitSiteService nonCanonicalSplitSiteService
    FeatureRelationshipService featureRelationshipService
    FeaturePropertyService featurePropertyService



    public
    static FeatureLocation convertJSONToFeatureLocation(JSONObject jsonLocation, Feature sourceFeature) throws JSONException {
        FeatureLocation gsolLocation = new FeatureLocation();
        gsolLocation.setFmin(jsonLocation.getInt(FeatureStringEnum.FMIN.value));
        gsolLocation.setFmax(jsonLocation.getInt(FeatureStringEnum.FMAX.value));
        gsolLocation.setStrand(jsonLocation.getInt(FeatureStringEnum.STRAND.value));
        gsolLocation.setSourceFeature(sourceFeature);
        return gsolLocation;
    }

    /** Get features that overlap a given location.  Compares strand as well as coordinates.
     *
     * @param location - FeatureLocation that the features overlap
     * @return Collection of Feature objects that overlap the FeatureLocation
     */
//    public Collection<Feature> getOverlappingFeatures(FeatureLocation location) {
//        return getOverlappingFeatures(location, true);
//    }

    /** Get features that overlap a given location.
     *
     * @param location - FeatureLocation that the features overlap
     * @param compareStrands - Whether to compare strands in overlap
     * @return Collection of Feature objects that overlap the FeatureLocation
     */
    public Collection<Feature> getOverlappingFeatures(FeatureLocation location, boolean compareStrands = true ) {
//        LinkedList<Feature> overlappingFeatures = new LinkedList<Feature>();


        FeatureLocation.findAllBySourceFeatureAndStrandAndFminLessThanEquals(location.sourceFeature, location.strand, location.fmin)
//            if (compareStrands) {
//                eq("strand", location.strand)
//            }
        def results = FeatureLocation.withCriteria {
//            eq("sourceFeature", location.sourceFeature)
            or {
                and {
                    le("fmin", location.fmin)
                    gt("fmax", location.fmin)
                }
                and {
                    lt("fmin", location.fmax)
                    ge("fmax", location.fmax)
                }
            }
        }

        return results*.feature.unique()
    }

//        if (strandsOverlap &&
//                (thisFmin <= otherFmin && thisFmax > otherFmin ||
//                        thisFmin >= otherFmin && thisFmin < otherFmax))

//        int low = 0;
//        int high = Feature.count - 1;
//        int index = -1;
//        while (low <= high) {
//            int mid = (low + ((high - low) / 2)).intValue();
//            Feature.all.get(mid)
//            Feature feature = Feature.all.get(mid)
//            if (feature == null) {
////                uniqueNameToStoredUniqueName.remove(features.get(mid).getUniqueName());
////                features.remove(mid);
//                return getOverlappingFeatures(location, compareStrands);
//            }
//            if (overlaps(feature.featureLocation, location, compareStrands)) {
//                index = mid;
//                break;
//            } else if (feature.getFeatureLocation().getFmin() < location.getFmin()) {
//                low = mid + 1;
//            } else {
//                high = mid - 1;
//            }
//        }
//        if (index >= -1) {
//            for (int i = index; i >= 0; --i) {
//                Feature feature = Feature.all.get(i)
//                if (feature == null) {
////                    uniqueNameToStoredUniqueName.remove(features.get(i).getUniqueName());
////                    features.remove(i);
//                    return getOverlappingFeatures(location, compareStrands);
//                }
//                if (overlaps(feature.featureLocation, location, compareStrands)) {
//                    overlappingFeatures.addFirst(feature);
//                } else {
//                    break;
//                }
//            }
//            for (int i = index + 1; i < Feature.count; ++i) {
//                Feature feature = Feature.all.get(i)
//                if (overlaps(feature.featureLocation, location, compareStrands)) {
//                    overlappingFeatures.addLast(feature);
//                } else {
//                    break;
//                }
//            }
//        }
//    return overlappingFeatures;
//}

    void updateNewGsolFeatureAttributes(Feature gsolFeature, Feature sourceFeature) {

        gsolFeature.setIsAnalysis(false);
        gsolFeature.setIsObsolete(false);
//        gsolFeature.setDateCreated(new Date()); //new Timestamp(new Date().getTime()));
//        gsolFeature.setLastUpdated(new Date()); //new Timestamp(new Date().getTime()));
        if (sourceFeature != null) {
            gsolFeature.getFeatureLocations().iterator().next().setSourceFeature(sourceFeature);
        }

        // TODO: this may be a mistake, is different than the original code
        // you are iterating through all of the children in order to set the SourceFeature and analsysis
//        for (FeatureRelationship fr : gsolFeature.getChildFeatureRelationships()) {
        for (FeatureRelationship fr : gsolFeature.getParentFeatureRelationships()) {
            println "gsolFeature ${gsolFeature} - ${fr.childFeature}"
            updateNewGsolFeatureAttributes(fr.getChildFeature(), sourceFeature);
        }
    }

    /**
     * From Gene.addTranscript
     * @param jsonTranscript
     * @param trackName
     * @param isPseudogene
     * @return
     */
    def generateTranscript(JSONObject jsonTranscript, String trackName, boolean isPseudogene = false) {
        Gene gene = jsonTranscript.has(FeatureStringEnum.PARENT_ID.value) ? (Gene) Feature.findByUniqueName(jsonTranscript.getString(FeatureStringEnum.PARENT_ID.value)) : null;
        println "JSON transcript ${jsonTranscript}"
        println "has parent: ${jsonTranscript.has(FeatureStringEnum.PARENT_ID.value)}"
        println "gene ${gene}"
        trackName = trackName.startsWith("Annotations-") ? trackName.substring("Annotations-".size()) : trackName
        println "sequence ${trackName}"
        Transcript transcript = null
        boolean useCDS = configWrapperService.useCDS()

        // TODO: not sure if this is a good idea or not
        Sequence sequence = Sequence.findByName(trackName)
        println "# SEQUENCEs: ${Sequence.count}"
        println "FIRST SEQUENCE: ${Sequence.first().name}"
        println "FIRST SEQUENCE roganism: ${Sequence.first().organism.commonName}"
        println "organism name: ${sequence.organism.commonName}"
//        Organism organism = sequence.organism


        FeatureLazyResidues featureLazyResidues = FeatureLazyResidues.findByName(trackName)
        println "featureLazyResidues ${featureLazyResidues}"
        // if the gene is set, then don't process, just set the transcript for the found gene
        if (gene != null) {
            println "has a gene! ${gene}"
//            Feature gsolTranscript = convertJSONToFeature(jsonTranscript, featureLazyResidues);
            transcript = (Transcript) convertJSONToFeature(jsonTranscript, featureLazyResidues, sequence);
//            transcript = (Transcript) BioObjectUtil.createBioObject(gsolTranscript, bioObjectConfiguration);
            if (transcript.getFmin() < 0 || transcript.getFmax() < 0) {
                throw new AnnotationException("Feature cannot have negative coordinates")
//                throw new AnnotationEditorServiceException("Feature cannot have negative coordinates");
            }

//            setOwner(transcript, (String) session.getAttribute("username"));
            featurePropertyService.setOwner(transcript, (String) SecurityUtils?.subject?.principal);

            if (!useCDS || transcriptService.getCDS(transcript) == null) {
                calculateCDS(transcript);
            }

            addTranscriptToGene(gene, transcript);
            nonCanonicalSplitSiteService.findNonCanonicalAcceptorDonorSpliceSites(transcript);
            transcript.name = nameService.generateUniqueName(transcript)
//            transcriptService.updateTranscriptAttributes(transcript);
        } else {
            println "there IS no gene! ${gene}"
            FeatureLocation featureLocation = convertJSONToFeatureLocation(jsonTranscript.getJSONObject(FeatureStringEnum.LOCATION.value), featureLazyResidues)
            println "has a feature location ${featureLocation}"
            Collection<Feature> overlappingFeatures = getOverlappingFeatures(featureLocation);
            println "overlapping features . . . . ${overlappingFeatures.size()}"
            for (Feature feature : overlappingFeatures) {
                if (!gene && feature instanceof Gene && !(feature instanceof Pseudogene) && configWrapperService.overlapper != null) {
                    Gene tmpGene = (Gene) feature;
                    println "found an overlpaping gene ${tmpGene}"
                    Transcript tmpTranscript = (Transcript) convertJSONToFeature(jsonTranscript, featureLazyResidues, sequence);
                    updateNewGsolFeatureAttributes(tmpTranscript, featureLazyResidues);
//                    Transcript tmpTranscript = (Transcript) BioObjectUtil.createBioObject(gsolTranscript, bioObjectConfiguration);
                    if (tmpTranscript.getFmin() < 0 || tmpTranscript.getFmax() < 0) {
                        throw new AnnotationException("Feature cannot have negative coordinates");
                    }
//                    setOwner(tmpTranscript, (String) session.getAttribute("username"));
                    String username = null
                    try {
                        username = SecurityUtils?.subject?.principal
                    } catch (e ) {
                        log.error(e)
                        username = "demo@demo.gov"
                    }
                    println "username: ${username}"
//                    featurePropertyService.setOwner(transcript, (String) SecurityUtils?.subject?.principal);
                    featurePropertyService.setOwner(tmpTranscript, username);
                    if (!useCDS || transcriptService.getCDS(tmpTranscript) == null) {
                        calculateCDS(tmpTranscript);
                    }
//                    tmpTranscript.name = nameService.generateUniqueName(transcript)
                    tmpTranscript.name = nameService.generateUniqueName(tmpTranscript,tmpGene.name)
//                    updateTranscriptAttributes(tmpTranscript);
                    if (overlaps(tmpTranscript, tmpGene)) {
                        println "there is an overlap . . . adding to an existing gene? "
                        transcript = tmpTranscript;
                        gene = tmpGene;
                        addTranscriptToGene(gene, transcript)
                        nonCanonicalSplitSiteService.findNonCanonicalAcceptorDonorSpliceSites(transcript);
                        transcript.save()
                        // was existing
                        gene.save(insert:false,flush:true)
                        break;
                    } else {
                        println "there is no overlap .  . we are going to return a NULL gene and a NULL transcript "
//                        editor.getSession().endTransactionForFeature(feature);
                    }
                } else {
                    println "!!!feature is not an instance of a gene or is a pseudogene or there is no adequate overlapper specified"
//                    editor.getSession().endTransactionForFeature(feature);
                }
            }
        }
        println "is gene null? ${gene}"
        println "is transcript null? ${transcript}"
        if (gene == null) {
            JSONObject jsonGene = new JSONObject();
            println "JSON TRANSCRIPT: " + jsonTranscript
            jsonGene.put(FeatureStringEnum.CHILDREN.value, new JSONArray().put(jsonTranscript));
            println "JSON GENE: " + jsonGene
            jsonGene.put(FeatureStringEnum.LOCATION.value, jsonTranscript.getJSONObject(FeatureStringEnum.LOCATION.value));
//            CVTerm cvTerm = CVTerm.findByName(isPseudogene ? FeatureStringEnum.PSEUDOGENE.value :FeatureStringEnum.GENE.value )
            String cvTermString = isPseudogene ? FeatureStringEnum.PSEUDOGENE.value : FeatureStringEnum.GENE.value
//            CVTerm cvTerm = new CVTerm()
//            jsonGene.put(FeatureStringEnum.TYPE.value, cvTermService.convertCVTermToJSON(cvTerm));
            jsonGene.put(FeatureStringEnum.TYPE.value, convertCVTermToJSON(FeatureStringEnum.CV.value, cvTermString));
            jsonGene.put(FeatureStringEnum.NAME.value,jsonTranscript.getString(FeatureStringEnum.NAME.value))

//            Feature gsolGene = convertJSONToFeature(jsonGene, featureLazyResidues);
            gene = (Gene) convertJSONToFeature(jsonGene, featureLazyResidues, sequence);
            updateNewGsolFeatureAttributes(gene, featureLazyResidues);
//            gene = (Gene) BioObjectUtil.createBioObject(gsolGene, bioObjectConfiguration);
            if (gene.getFmin() < 0 || gene.getFmax() < 0) {
                throw new AnnotationException("Feature cannot have negative coordinates");
            }
//            featurePropertyService.setOwner(gene, (String) SecurityUtils?.subject?.principal ?: "demo@demo.gov");
            println "gene ${gene}"
            println "gene ${gene.parentFeatureRelationships}"
            transcript = transcriptService.getTranscripts(gene).iterator().next();
            if (!useCDS || transcriptService.getCDS(transcript) == null) {
                calculateCDS(transcript);
            }
            // I don't thikn that this does anything
            addFeature(gene)
            transcript.name = nameService.generateUniqueName(transcript)
            nonCanonicalSplitSiteService.findNonCanonicalAcceptorDonorSpliceSites(transcript);
            gene.save(insert: true)
            transcript.save(flush: true)
        }
        return transcript;

    }

// TODO: this is kind of a hack for now
    JSONObject convertCVTermToJSON(String cv, String cvTerm) {
        JSONObject jsonCVTerm = new JSONObject();
        JSONObject jsonCV = new JSONObject();
        jsonCVTerm.put(FeatureStringEnum.CV.value, jsonCV);
        jsonCV.put(FeatureStringEnum.NAME.value, cv);
        jsonCVTerm.put(FeatureStringEnum.NAME.value, cvTerm);
        return jsonCVTerm;
    }


    Feature getTopLevelFeature(Feature feature) {
        Collection<? extends Feature> parents = feature.getParentFeatureRelationships()*.parentFeature
        if (parents.size() > 0) {
            return getTopLevelFeature(parents.iterator().next());
        } else {
            return feature;
        }
    }


    def addFeature(Feature feature) {

//        Feature topLevelFeature = getTopLevelFeature(feature);

        if (feature instanceof Gene) {
            for (Transcript transcript : transcriptService.getTranscripts((Gene) feature)) {
                removeExonOverlapsAndAdjacencies(transcript);
            }
        } else if (feature instanceof Transcript) {
            removeExonOverlapsAndAdjacencies((Transcript) feature);
        }

        // event fire
//        fireAnnotationChangeEvent(feature, topLevelFeature, AnnotationChangeEvent.Operation.ADD);

//        getSession().addFeature(feature);

        // old version was deleting the feature .. . badness!! , we want to update if its there.

//        if (uniqueNameToStoredUniqueName.containsKey(feature.getUniqueName())) {
//            addSequenceAlterationdeleteFeature(feature);
//        }
//        Feature topLevelFeature = getTopLevelFeature(feature);
//        features.add(new FeatureData(topLevelFeature));
//        if (getFeatureByUniqueName(topLevelFeature.getUniqueName()) == null) {
//            beginTransactionForFeature(topLevelFeature);
//        }
//        indexFeature(topLevelFeature);
//        Collections.sort(features, new FeatureDataPositionComparator());

    }

    def addTranscriptToGene(Gene gene, Transcript transcript) {
        println "transcript exists ${transcript}"
        removeExonOverlapsAndAdjacencies(transcript);
//        gene.addTranscript(transcript);
//        CVTerm partOfCvterm = cvTermService.partOf

        // no feature location, set location to transcript's
        if (gene.getSingleFeatureLocation() == null) {
            FeatureLocation transcriptFeatureLocation = transcript.getSingleFeatureLocation()
            FeatureLocation featureLocation = new FeatureLocation()
            featureLocation.properties = transcriptFeatureLocation.properties
            featureLocation.id = null
            featureLocation.save()
            gene.addToFeatureLocations(featureLocation);
        } else {
            // if the transcript's bounds are beyond the gene's bounds, need to adjust the gene's bounds
            if (transcript.getSingleFeatureLocation().getFmin() < gene.getSingleFeatureLocation().getFmin()) {
                gene.getSingleFeatureLocation().setFmin(transcript.getSingleFeatureLocation().getFmin());
            }
            if (transcript.getSingleFeatureLocation().getFmax() > gene.getSingleFeatureLocation().getFmax()) {
                gene.getSingleFeatureLocation().setFmax(transcript.getSingleFeatureLocation().getFmax());
            }
        }

        // add transcript
        int rank = 0;
        //TODO: do we need to figure out the rank?
        FeatureRelationship featureRelationship = new FeatureRelationship(
//                type: partOfCvterm.iterator().next()
                parentFeature: gene
                , childFeature: transcript
//                , rank: rank
        ).save(failOnError: true)
        gene.addToParentFeatureRelationships(featureRelationship)
        transcript.addToChildFeatureRelationships(featureRelationship)


        updateGeneBoundaries(gene);

//        getSession().indexFeature(transcript);

        // event fire
//        TODO: determine event model?
//        fireAnnotationChangeEvent(transcript, gene, AnnotationChangeEvent.Operation.ADD);
    }


    def removeExonOverlapsAndAdjacencies(Transcript transcript) {
        Collection<Exon> exons = transcriptService.getExons(transcript)
        if (transcriptService.getExons(transcript).size() <= 1) {
            return;
        }
        List<Exon> sortedExons = new LinkedList<Exon>(exons);
        Collections.sort(sortedExons, new FeaturePositionComparator<Exon>(false))
        int inc = 1;
        for (int i = 0; i < sortedExons.size() - 1; i += inc) {
            inc = 1;
            Exon leftExon = sortedExons.get(i);
            for (int j = i + 1; j < sortedExons.size(); ++j) {
                Exon rightExon = sortedExons.get(j);
                overlaps(leftExon, rightExon)
                if (overlaps(leftExon, rightExon) || isAdjacentTo(leftExon.getFeatureLocation(), rightExon.getFeatureLocation())) {
                    try {
                        exonService.mergeExons(leftExon, rightExon);
                    } catch (AnnotationException e) {
                        log.error(e)
                    }
                    ++inc;
                }
            }
        }
    }

/** Checks whether this AbstractSimpleLocationBioFeature is adjacent to the FeatureLocation.
 *
 * @param location - FeatureLocation to check adjacency against
 * @return true if there is adjacency
 */
    public boolean isAdjacentTo(FeatureLocation leftLocation, FeatureLocation location) {
        return isAdjacentTo(leftLocation, location, true);
    }

    public boolean isAdjacentTo(FeatureLocation leftFeatureLocation, FeatureLocation rightFeatureLocation, boolean compareStrands) {
        if (leftFeatureLocation.getSourceFeature() != rightFeatureLocation.getSourceFeature() &&
                !leftFeatureLocation.getSourceFeature().equals(rightFeatureLocation.getSourceFeature())) {
            return false;
        }
        int thisFmin = leftFeatureLocation.getFmin();
        int thisFmax = leftFeatureLocation.getFmax();
        int thisStrand = leftFeatureLocation.getStrand();
        int otherFmin = rightFeatureLocation.getFmin();
        int otherFmax = rightFeatureLocation.getFmax();
        int otherStrand = rightFeatureLocation.getStrand();
        boolean strandsOverlap = compareStrands ? thisStrand == otherStrand : true;
        if (strandsOverlap &&
                (thisFmax == otherFmin ||
                        thisFmin == otherFmax)) {
            return true;
        }
        return false;
    }

    boolean overlaps(Feature leftFeature, Feature rightFeature, boolean compareStrands = true) {
        return overlaps(leftFeature.featureLocation, rightFeature.featureLocation, compareStrands)
    }

    boolean overlaps(FeatureLocation leftFeatureLocation, FeatureLocation rightFeatureLocation, boolean compareStrands = true) {
        if (leftFeatureLocation.getSourceFeature() != rightFeatureLocation.getSourceFeature() &&
                !leftFeatureLocation.getSourceFeature().equals(rightFeatureLocation.getSourceFeature())) {
            return false;
        }
        int thisFmin = leftFeatureLocation.getFmin();
        int thisFmax = leftFeatureLocation.getFmax();
        int thisStrand = leftFeatureLocation.getStrand();
        int otherFmin = rightFeatureLocation.getFmin();
        int otherFmax = rightFeatureLocation.getFmax();
        int otherStrand = rightFeatureLocation.getStrand();
        boolean strandsOverlap = compareStrands ? thisStrand == otherStrand : true;
        if (strandsOverlap &&
                (thisFmin <= otherFmin && thisFmax > otherFmin ||
                        thisFmin >= otherFmin && thisFmin < otherFmax)) {
            return true;
        }
        return false;
    }


    def calculateCDS(Transcript transcript) {
        // NOTE: isPseudogene call seemed redundant with isProtenCoding
        calculateCDS(transcript, false)
//        if (transcriptService.isProteinCoding(transcript) && (transcriptService.getGene(transcript) == null)) {
////            calculateCDS(editor, transcript, transcript.getCDS() != null ? transcript.getCDS().getStopCodonReadThrough() != null : false);
////            calculateCDS(transcript, transcript.getCDS() != null ? transcript.getCDS().getStopCodonReadThrough() != null : false);
//            calculateCDS(transcript, transcriptService.getCDS(transcript) != null ? transcriptService.getStopCodonReadThrough(transcript) != null : false);
//        }
    }

    def calculateCDS(Transcript transcript, boolean readThroughStopCodon) {
        CDS cds = transcriptService.getCDS(transcript);
        if (cds == null) {
            setLongestORF(transcript, readThroughStopCodon);
            return;
        }
        boolean manuallySetStart = cdsService.isManuallySetTranslationStart(cds);
        boolean manuallySetEnd = cdsService.isManuallySetTranslationEnd(cds);
        if (manuallySetStart && manuallySetEnd) {
            return;
        }
        if (!manuallySetStart && !manuallySetEnd) {
            setLongestORF(transcript, readThroughStopCodon);
        } else if (manuallySetStart) {
            setTranslationStart(transcript, cds.getFeatureLocation().getStrand().equals(-1) ? cds.getFmax() - 1 : cds.getFmin(), true, readThroughStopCodon);
        } else {
            setTranslationEnd(transcript, cds.getFeatureLocation().getStrand().equals(-1) ? cds.getFmin() : cds.getFmax() - 1, true);
        }
    }



/**
 * Calculate the longest ORF for a transcript.  If a valid start codon is not found, allow for partial CDS start/end.
 * Calls setLongestORF(Transcript, TranslationTable, boolean) with the translation table and whether partial
 * ORF calculation extensions are allowed from the configuration associated with this editor.
 *
 * @param transcript - Transcript to set the longest ORF to
 */
    public void setLongestORF(Transcript transcript, boolean readThroughStopCodon) {
        setLongestORF(transcript, configWrapperService.getTranslationTable(), false, readThroughStopCodon);
    }

    public void setLongestORF(Transcript transcript) {
        setLongestORF(transcript, false);
    }

/**
 * Set the translation start in the transcript.  Sets the translation start in the underlying CDS feature.
 * Instantiates the CDS object for the transcript if it doesn't already exist.
 *
 * @param transcript - Transcript to set the translation start in
 * @param translationStart - Coordinate of the start of translation
 */
    public void setTranslationStart(Transcript transcript, int translationStart) {
        setTranslationStart(transcript, translationStart, false);
    }

/**
 * Set the translation start in the transcript.  Sets the translation start in the underlying CDS feature.
 * Instantiates the CDS object for the transcript if it doesn't already exist.
 *
 * @param transcript - Transcript to set the translation start in
 * @param translationStart - Coordinate of the start of translation
 * @param setTranslationEnd - if set to true, will search for the nearest in frame stop codon
 */
    public void setTranslationStart(Transcript transcript, int translationStart, boolean setTranslationEnd) {
        setTranslationStart(transcript, translationStart, setTranslationEnd, false);
    }

/**
 * Set the translation start in the transcript.  Sets the translation start in the underlying CDS feature.
 * Instantiates the CDS object for the transcript if it doesn't already exist.
 *
 * @param transcript - Transcript to set the translation start in
 * @param translationStart - Coordinate of the start of translation
 * @param setTranslationEnd - if set to true, will search for the nearest in frame stop codon
 * @param readThroughStopCodon - if set to true, will read through the first stop codon to the next
 */
    public void setTranslationStart(Transcript transcript, int translationStart, boolean setTranslationEnd, boolean readThroughStopCodon) {
        setTranslationStart(transcript, translationStart, setTranslationEnd, setTranslationEnd ? configWrapperService.getTranslationTable() : null, readThroughStopCodon);
    }

/** Convert local coordinate to source feature coordinate.
 *
 * @param localCoordinate - Coordinate to convert to source coordinate
 * @return Source feature coordinate, -1 if local coordinate is longer than feature's length or negative
 */
    public int convertLocalCoordinateToSourceCoordinate(Feature feature, int localCoordinate) {
        if (localCoordinate < 0 || localCoordinate > feature.getLength()) {
            return -1;
        }
        if (feature.getFeatureLocation().getStrand() == -1) {
            return feature.getFeatureLocation().getFmax() - localCoordinate - 1;
        } else {
            return feature.getFeatureLocation().getFmin() + localCoordinate;
        }
    }

    public int convertModifiedLocalCoordinateToSourceCoordinate(Feature feature,
                                                                int localCoordinate) {
//        Transcript transcript = cdsService.getTranscript((CDS) feature )
        Transcript transcript = (Transcript) featureRelationshipService.getParentForFeature(feature, Transcript.ontologyId)
        List<SequenceAlteration> alterations = feature instanceof CDS ? getFrameshiftsAsAlterations(transcript) : new ArrayList<SequenceAlteration>();

//        alterations.addAll(dataStore.getSequenceAlterations());
        alterations.addAll(getAllSequenceAlterationsForFeature(feature))
        if (alterations.size() == 0) {
            return convertLocalCoordinateToSourceCoordinate(feature, localCoordinate);
        }
//        Collections.sort(alterations, new BioObjectUtil.FeaturePositionComparator<SequenceAlteration>());
        Collections.sort(alterations, new FeaturePositionComparator<SequenceAlteration>());
        if (feature.getFeatureLocation().getStrand() == -1) {
            Collections.reverse(alterations);
        }
        for (SequenceAlteration alteration : alterations) {
            if (!overlaps(feature, alteration)) {
                continue;
            }
            if (feature.getFeatureLocation().getStrand() == -1) {
                if (convertSourceCoordinateToLocalCoordinate(feature, alteration.getFeatureLocation().getFmin()) > localCoordinate) {
                    localCoordinate -= alteration.getOffset();
                }
            } else {
                if (convertSourceCoordinateToLocalCoordinate(feature, alteration.getFeatureLocation().getFmin()) < localCoordinate) {
                    localCoordinate -= alteration.getOffset();
                }
            }
        }
        return convertLocalCoordinateToSourceCoordinate(feature, localCoordinate);
    }

/**
 * Set the translation start in the transcript.  Sets the translation start in the underlying CDS feature.
 * Instantiates the CDS object for the transcript if it doesn't already exist.
 *
 * @param transcript - Transcript to set the translation start in
 * @param translationStart - Coordinate of the start of translation
 * @param setTranslationEnd - if set to true, will search for the nearest in frame stop codon
 * @param translationTable - Translation table that defines the codon translation
 * @param readThroughStopCodon - if set to true, will read through the first stop codon to the next
 */
    public void setTranslationStart(Transcript transcript, int translationStart, boolean setTranslationEnd, TranslationTable translationTable, boolean readThroughStopCodon) {
        CDS cds = transcriptService.getCDS(transcript);
        if (cds == null) {
            cds = transcriptService.createCDS(transcript);
            featureRelationshipService.addChildFeature(transcript, cds)
//            transcript.setCDS(cds);
        }
        if (transcript.getStrand() == -1) {
            setFmax(cds, translationStart + 1);
        } else {
            setFmin(cds, translationStart);
        }
        cdsService.setManuallySetTranslationStart(cds, true);
//        cds.deleteStopCodonReadThrough();
        cdsService.deleteStopCodonReadThrough(cds);
//        featureRelationshipService.deleteRelationships()
        if (setTranslationEnd && translationTable != null) {
            String mrna = getResiduesWithAlterationsAndFrameshifts(transcript);
            if (mrna == null || mrna.equals("null")) {
                return;
            }
            int stopCodonCount = 0;
            for (int i = convertSourceCoordinateToLocalCoordinate(transcript, translationStart); i < transcript.getLength(); i += 3) {
                if (i + 3 > mrna.length()) {
                    break;
                }
                String codon = mrna.substring(i, i + 3);
                if (translationTable.getStopCodons().contains(codon)) {
                    if (readThroughStopCodon && ++stopCodonCount < 2) {
//                        StopCodonReadThrough stopCodonReadThrough = cdsService.getStopCodonReadThrough(cds);
                        StopCodonReadThrough stopCodonReadThrough = (StopCodonReadThrough) featureRelationshipService.getChildForFeature(cds, StopCodonReadThrough.ontologyId)
                        if (stopCodonReadThrough == null) {
                            stopCodonReadThrough = cdsService.createStopCodonReadThrough(cds);
                            cdsService.setStopCodonReadThrough(cds, stopCodonReadThrough)
//                            cds.setStopCodonReadThrough(stopCodonReadThrough);
                            if (cds.getStrand() == -1) {
                                stopCodonReadThrough.featureLocation.setFmin(convertModifiedLocalCoordinateToSourceCoordinate(transcript, i + 3));
                                stopCodonReadThrough.featureLocation.setFmax(convertModifiedLocalCoordinateToSourceCoordinate(transcript, i) + 1);
                            } else {
                                stopCodonReadThrough.featureLocation.setFmin(convertModifiedLocalCoordinateToSourceCoordinate(transcript, i));
                                stopCodonReadThrough.featureLocation.setFmax(convertModifiedLocalCoordinateToSourceCoordinate(transcript, i + 3) + 1);
                            }
                        }
                        continue;
                    }
                    if (transcript.getStrand() == -1) {
                        cds.featureLocation.setFmin(convertLocalCoordinateToSourceCoordinate(transcript, i + 2));
                    } else {
                        cds.featureLocation.setFmax(convertLocalCoordinateToSourceCoordinate(transcript, i + 3));
                    }
                    return;
                }
            }
            if (transcript.getStrand() == -1) {
                cds.featureLocation.setFmin(transcript.getFmin());
                cds.featureLocation.setIsFminPartial(true);
            } else {
                cds.featureLocation.setFmax(transcript.getFmax());
                cds.featureLocation.setIsFmaxPartial(true);
            }
        }

        Date date = new Date();
        cds.setLastUpdated(date);
        transcript.setLastUpdated(date);

        // event fire
//        fireAnnotationChangeEvent(transcript, transcript.getGene(), AnnotationChangeEvent.Operation.UPDATE);

    }

/** Set the translation end in the transcript.  Sets the translation end in the underlying CDS feature.
 *  Instantiates the CDS object for the transcript if it doesn't already exist.
 *
 * @param transcript - Transcript to set the translation start in
 * @param translationEnd - Coordinate of the end of translation
 */
/*
public void setTranslationEnd(Transcript transcript, int translationEnd) {
    CDS cds = transcript.getCDS();
    if (cds == null) {
        cds = createCDS(transcript);
        transcript.setCDS(cds);
    }
    if (transcript.getStrand() == -1) {
        cds.setFmin(translationEnd + 1);
    }
    else {
        cds.setFmax(translationEnd);
    }
    setManuallySetTranslationEnd(cds, true);
    cds.deleteStopCodonReadThrough();

    // event fire
    fireAnnotationChangeEvent(transcript, transcript.getGene(), AnnotationChangeEvent.Operation.UPDATE);

}
*/

/**
 * Set the translation end in the transcript.  Sets the translation end in the underlying CDS feature.
 * Instantiates the CDS object for the transcript if it doesn't already exist.
 *
 * @param transcript - Transcript to set the translation end in
 * @param translationEnd - Coordinate of the end of translation
 */
    public void setTranslationEnd(Transcript transcript, int translationEnd) {
        setTranslationEnd(transcript, translationEnd, false);
    }

/**
 * Set the translation end in the transcript.  Sets the translation end in the underlying CDS feature.
 * Instantiates the CDS object for the transcript if it doesn't already exist.
 *
 * @param transcript - Transcript to set the translation end in
 * @param translationEnd - Coordinate of the end of translation
 * @param setTranslationStart - if set to true, will search for the nearest in frame start
 */
    public void setTranslationEnd(Transcript transcript, int translationEnd, boolean setTranslationStart) {
        setTranslationEnd(transcript, translationEnd, setTranslationStart,
                setTranslationStart ? configWrapperService.getTranslationTable() : null
        );
    }

/**
 * Set the translation end in the transcript.  Sets the translation end in the underlying CDS feature.
 * Instantiates the CDS object for the transcript if it doesn't already exist.
 *
 * @param transcript - Transcript to set the translation end in
 * @param translationEnd - Coordinate of the end of translation
 * @param setTranslationStart - if set to true, will search for the nearest in frame start codon
 * @param translationTable - Translation table that defines the codon translation
 */
    public void setTranslationEnd(Transcript transcript, int translationEnd, boolean setTranslationStart, TranslationTable translationTable) {
        CDS cds = transcriptService.getCDS(transcript);
        if (cds == null) {
            cds = transcriptService.createCDS(transcript);
            transcriptService.setCDS(transcript, cds);
        }
        if (transcript.getStrand() == -1) {
            cds.featureLocation.setFmin(translationEnd);
        } else {
            cds.featureLocation.setFmax(translationEnd + 1);
        }
        cdsService.setManuallySetTranslationEnd(cds, true);
        cdsService.deleteStopCodonReadThrough(cds);
        if (setTranslationStart && translationTable != null) {
            String mrna = getResiduesWithAlterationsAndFrameshifts(transcript);
            if (mrna == null || mrna.equals("null")) {
                return;
            }
            for (int i = convertSourceCoordinateToLocalCoordinate(transcript, translationEnd) - 3; i >= 0; i -= 3) {
                if (i - 3 < 0) {
                    break;
                }
                String codon = mrna.substring(i, i + 3);
                if (translationTable.getStartCodons().contains(codon)) {
                    if (transcript.getStrand() == -1) {
                        cds.featureLocation.setFmax(convertLocalCoordinateToSourceCoordinate(transcript, i + 3));
                    } else {
                        cds.featureLocation.setFmin(convertLocalCoordinateToSourceCoordinate(transcript, i + 2));
                    }
                    return;
                }
            }
            if (transcript.getStrand() == -1) {
                cds.featureLocation.setFmin(transcript.getFmin());
                cds.featureLocation.setIsFminPartial(true);
            } else {
                cds.featureLocation.setFmax(transcript.getFmax());
                cds.featureLocation.setIsFmaxPartial(true);
            }
        }

        Date date = new Date();
        cds.setLastUpdated(date);
        transcript.setLastUpdated(date);

        // event fire TODO: ??
//        fireAnnotationChangeEvent(transcript, transcript.getGene(), AnnotationChangeEvent.Operation.UPDATE);

    }

    void setTranslationFmin(Transcript transcript, int translationFmin) {
        CDS cds = transcriptService.getCDS(transcript);
        if (cds == null) {
            cds = transcriptService.createCDS(transcript);
            transcriptService.setCDS(transcript, cds);
        }
        setFmin(cds, translationFmin);

        // event fire
//        fireAnnotationChangeEvent(transcript, transcript.getGene(), AnnotationChangeEvent.Operation.UPDATE);

    }

    void setTranslationFmax(Transcript transcript, int translationFmax) {
        CDS cds = transcriptService.getCDS(transcript);
        if (cds == null) {
            cds = transcriptService.createCDS(transcript);
            transcriptService.setCDS(transcript, cds);
        }
        setFmax(cds, translationFmax);

        // event fire
//        fireAnnotationChangeEvent(transcript, transcript.getGene(), AnnotationChangeEvent.Operation.UPDATE);

    }

/**
 * Set the translation start and end in the transcript.  Sets the translation start and end in the underlying CDS
 * feature.  Instantiates the CDS object for the transcript if it doesn't already exist.
 *
 * @param transcript - Transcript to set the translation start in
 * @param translationStart - Coordinate of the start of translation
 * @param translationEnd - Coordinate of the end of translation
 * @param manuallySetStart - whether the start was manually set
 * @param manuallySetEnd - whether the end was manually set
 */
    public void setTranslationEnds(Transcript transcript, int translationStart, int translationEnd, boolean manuallySetStart, boolean manuallySetEnd) {
        setTranslationFmin(transcript, translationStart);
        setTranslationFmax(transcript, translationEnd);
        cdsService.setManuallySetTranslationStart(transcriptService.getCDS(transcript), manuallySetStart);
        cdsService.setManuallySetTranslationEnd(transcriptService.getCDS(transcript), manuallySetEnd);

        Date date = new Date();
        transcriptService.getCDS(transcript).setLastUpdated(date);
        transcript.setLastUpdated(date);

        // event fire
//        fireAnnotationChangeEvent(transcript, transcript.getGene(), AnnotationChangeEvent.Operation.UPDATE);

    }


    public void setLongestORF(Transcript transcript, TranslationTable translationTable, boolean allowPartialExtension) {
        setLongestORF(transcript, translationTable, allowPartialExtension, false);
    }

/** Get the residues for a feature with any alterations and frameshifts.
 *
 * @param feature - Feature to retrieve the residues for
 * @return Residues for the feature with any alterations and frameshifts
 */
    public String getResiduesWithAlterationsAndFrameshifts(Feature feature) {
        if (!(feature instanceof CDS)) {
            return getResiduesWithAlterations(feature, SequenceAlteration.all);
        }
//        Transcript transcript = cdsService.getTranscript((CDS) feature)
        Transcript transcript = (Transcript) featureRelationshipService.getParentForFeature(feature, Transcript.ontologyId)
        Collection<SequenceAlteration> alterations = getFrameshiftsAsAlterations(transcript);

        List<SequenceAlteration> allSequenceAlterationList = getAllSequenceAlterationsForFeature(feature)

//        List<SequenceAlteration> sequenceAlterationList = sequences.featureLocations.
//        alterations.addAll(dataStore.getSequenceAlterations());
        alterations.addAll(allSequenceAlterationList);
        return getResiduesWithAlterations(feature, alterations);
    }

    List<SequenceAlteration> getAllSequenceAlterationsForFeature(Feature feature) {
        List<Sequence> sequences = feature.featureLocations*.sequence
        List<SequenceAlteration> allSequenceAlterationList = SequenceAlteration.executeQuery(
                "select sa from  SequenceAlteration sa where sa.featureLocation.sequence in (:sequences) "
                , [sequences: sequences])
        return allSequenceAlterationList
    }

    List<SequenceAlteration> getFrameshiftsAsAlterations(Transcript transcript) {
        List<SequenceAlteration> frameshifts = new ArrayList<SequenceAlteration>();
        CDS cds = transcriptService.getCDS(transcript);
        if (cds == null) {
            return frameshifts;
        }
//        AbstractBioFeature sourceFeature =
//                (AbstractBioFeature)BioObjectUtil.createBioObject(cds.getFeatureLocation().getSourceFeature(),
//                        cds.getConfiguration());
        Feature sourceFeature = cds.getFeatureLocation().getSourceFeature()
        List<Frameshift> frameshiftList = transcriptService.getFrameshifts(transcript)
        for (Frameshift frameshift : frameshiftList) {
            if (frameshift.isPlusFrameshift()) {
                // a plus frameshift skips bases during translation, which can be mapped to a deletion for the
                // the skipped bases
//                Deletion deletion = new Deletion(cds.getOrganism(), "Deletion-" + frameshift.getCoordinate(), false,
//                        false, new Timestamp(new Date().getTime()), cds.getConfiguration());

                FeatureLocation featureLocation = new FeatureLocation(
                        fmin: frameshift.coordinate
                        , fmax: frameshift.coordinate + frameshift.frameshiftValue
                        , strand: cds.featureLocation.strand
                        , sourceFeature: sourceFeature
                )

                Deletion deletion = new Deletion(
                        uniqueName: FeatureStringEnum.DELETION_PREFIX.value + frameshift.coordinate
                        , isObsolete: false
                        , isAnalysis: false
                )

                featureLocation.feature = deletion
                deletion.addToFeatureLocations(featureLocation)

                frameshifts.add(deletion);
                featureLocation.save()
                deletion.save()
                frameshift.save(flush: true)

//                deletion.setFeatureLocation(frameshift.getCoordinate(),
//                        frameshift.getCoordinate() + frameshift.getFrameshiftValue(),
//                        cds.getFeatureLocation().getStrand(), sourceFeature);


            } else {
                // a minus frameshift goes back bases during translation, which can be mapped to an insertion for the
                // the repeated bases
                Insertion insertion = new Insertion(
                        uniqueName: FeatureStringEnum.INSERTION_PREFIX.value + frameshift.coordinate
                        , isAnalysis: false
                        , isObsolete: false
                ).save()

//                Insertion insertion = new Insertion(cds.getOrganism(), "Insertion-" + frameshift.getCoordinate(), false,
//                        false, new Timestamp(new Date().getTime()), cds.getConfiguration());

                FeatureLocation featureLocation = new FeatureLocation(
                        fmin: frameshift.coordinate
                        , fmax: frameshift.coordinate + frameshift.frameshiftValue
                        , strand: cds.featureLocation.strand
                        , sourceFeature: sourceFeature
                ).save()

                insertion.addToFeatureLocations(featureLocation)
                featureLocation.feature = insertion

//                insertion.setFeatureLocation(frameshift.getCoordinate() + frameshift.getFrameshiftValue(),
//                        frameshift.getCoordinate() + frameshift.getFrameshiftValue(),
//                        cds.getFeatureLocation().getStrand(), sourceFeature);
                insertion.setResidues(sourceFeature.getResidues().substring(
                        frameshift.getCoordinate() + frameshift.getFrameshiftValue(), frameshift.getCoordinate()));
                frameshifts.add(insertion);

                insertion.save()
                featureLocation.save()
                frameshift.save(flush: true)
            }
        }
        return frameshifts;
    }

/**
 * Calculate the longest ORF for a transcript.  If a valid start codon is not found, allow for partial CDS start/end.
 *
 * @param transcript - Transcript to set the longest ORF to
 * @param translationTable - Translation table that defines the codon translation
 * @param allowPartialExtension - Where partial ORFs should be used for possible extension
 */
    public void setLongestORF(Transcript transcript, TranslationTable translationTable, boolean allowPartialExtension, boolean readThroughStopCodon) {
        String mrna = getResiduesWithAlterationsAndFrameshifts(transcript);
        if (mrna == null || mrna.equals("null")) {
            return;
        }
        String longestPeptide = "";
        int bestStartIndex = -1;
        int bestStopIndex = -1;
        boolean partialStop = false;

        if (mrna.length() > 3) {
            for (String startCodon : translationTable.getStartCodons()) {
                int startIndex = mrna.indexOf(startCodon);
                while (startIndex >= 0) {
                    String mrnaSubstring = mrna.substring(startIndex);
                    String aa = SequenceTranslationHandler.translateSequence(mrnaSubstring, translationTable, true, readThroughStopCodon);
                    if (aa.length() > longestPeptide.length()) {
                        longestPeptide = aa;
                        bestStartIndex = startIndex;
                        bestStopIndex = startIndex + (aa.length() * 3);
                        if (!longestPeptide.substring(longestPeptide.length() - 1).equals(TranslationTable.STOP)) {
                            partialStop = true;
                            bestStopIndex += mrnaSubstring.length() % 3;
                        }
                    }
                    startIndex = mrna.indexOf(startCodon, startIndex + 1);
                }
            }
        }

        CDS cds = transcriptService.getCDS(transcript)
        boolean needCdsIndex = cds == null;
        if (cds == null) {
            cds = transcriptService.createCDS(transcript);
            transcriptService.setCDS(transcript, cds);
        }
        if (bestStartIndex >= 0) {
            int fmin = convertModifiedLocalCoordinateToSourceCoordinate(transcript, bestStartIndex);
            int fmax = convertModifiedLocalCoordinateToSourceCoordinate(transcript, bestStopIndex);
            if (cds.getStrand().equals(-1)) {
                int tmp = fmin;
                fmin = fmax + 1;
                fmax = tmp + 1;
            }
            setFmin(cds, fmin);
            cds.featureLocation.setIsFminPartial(false);
            setFmax(cds, fmax);
            cds.featureLocation.setIsFmaxPartial(partialStop);
        } else {
            setFmin(cds, transcript.getFmin());
            cds.featureLocation.setIsFminPartial(true);
            String aa = SequenceTranslationHandler.translateSequence(mrna, translationTable, true, readThroughStopCodon);
            if (aa.substring(aa.length() - 1).equals(TranslationTable.STOP)) {
                setFmax(cds, convertModifiedLocalCoordinateToSourceCoordinate(transcript, aa.length() * 3));
                cds.featureLocation.setIsFmaxPartial(false);
            } else {
                setFmax(cds, transcript.getFmax());
                cds.featureLocation.setIsFmaxPartial(true);
            }
        }
        if (readThroughStopCodon) {
            String aa = SequenceTranslationHandler.translateSequence(getResiduesWithAlterationsAndFrameshifts(cds), translationTable, true, true);
            int firstStopIndex = aa.indexOf(TranslationTable.STOP);
            if (firstStopIndex < aa.length() - 1) {
                StopCodonReadThrough stopCodonReadThrough = cdsService.createStopCodonReadThrough(cds);
                cdsService.setStopCodonReadThrough(cds, stopCodonReadThrough);
                int offset = transcript.getStrand() == -1 ? -2 : 0;
                setFmin(stopCodonReadThrough, convertModifiedLocalCoordinateToSourceCoordinate(cds, firstStopIndex * 3) + offset);
                setFmax(stopCodonReadThrough, convertModifiedLocalCoordinateToSourceCoordinate(cds, firstStopIndex * 3) + 3 + offset);
            }
        } else {
            cdsService.deleteStopCodonReadThrough(cds);
        }
        cdsService.setManuallySetTranslationStart(cds, false);
        cdsService.setManuallySetTranslationEnd(cds, false);

//        if (needCdsIndex) {
//            getSession().indexFeature(cds);
//        }

        Date date = new Date();
        cds.setLastUpdated(date);
        transcript.setLastUpdated(date);

        // event fire
//        fireAnnotationChangeEvent(transcript, transcript.getGene(), AnnotationEditor.AnnotationChangeEvent.Operation.UPDATE);

    }


    public Feature convertJSONToFeature(JSONObject jsonFeature, Feature sourceFeature, Sequence sequence) {
        Feature gsolFeature
        try {
//            gsolFeature.setOrganism(organism);

            // TODO: JSON type feature not set
            JSONObject type = jsonFeature.getJSONObject(FeatureStringEnum.TYPE.value);
            println "JSON FEATURE ${jsonFeature.toString()}"
            println "type ${type}"
            String ontologyId = convertJSONToOntologyId(type)
            println "ontology Id ${ontologyId}"
            gsolFeature = generateFeatureForType(ontologyId)
            println "Created feature: ${gsolFeature}"
            println "source feature: ${sourceFeature}"
//            Sequence sequence = Sequence.findByName(jsonFeature.get(AnnotationEditorController.REST_TRACK).toString())
            println "found sequnce: ${sequence}"
//            gsolFeature.setType(cvTermService.convertJSONToCVTerm(type));
//            gsolFeature.ontologyId = (cvTermService.convertJSONToCVTerm(type));
//            gsolFeature.ontologyId = convertJSONToOntologyId(type)

            if (jsonFeature.has(FeatureStringEnum.UNIQUENAME.value)) {
                gsolFeature.setUniqueName(jsonFeature.getString(FeatureStringEnum.UNIQUENAME.value));
            } else {
                gsolFeature.setUniqueName(nameService.generateUniqueName());
            }
            if (jsonFeature.has(FeatureStringEnum.NAME.value)) {
                println "HAS name ${jsonFeature.getString(FeatureStringEnum.NAME.value)}"
                gsolFeature.setName(jsonFeature.getString(FeatureStringEnum.NAME.value));
            } else {
                println "NO name using unique name"
                gsolFeature.name = gsolFeature.uniqueName
            }
            if (jsonFeature.has(FeatureStringEnum.RESIDUES.value)) {
                gsolFeature.setResidues(jsonFeature.getString(FeatureStringEnum.RESIDUES.value));
            }

            gsolFeature.save(failOnError: true)
            if (jsonFeature.has(FeatureStringEnum.LOCATION.value)) {
                JSONObject jsonLocation = jsonFeature.getJSONObject(FeatureStringEnum.LOCATION.value);
                FeatureLocation featureLocation = convertJSONToFeatureLocation(jsonLocation, sourceFeature)
                featureLocation.sequence = sequence
                featureLocation.feature = gsolFeature
                featureLocation.save(failOnError: true)
                gsolFeature.addToFeatureLocations(featureLocation);
            }

            gsolFeature.save(failOnError: true)

            if (jsonFeature.has(FeatureStringEnum.CHILDREN.value)) {
                JSONArray children = jsonFeature.getJSONArray(FeatureStringEnum.CHILDREN.value);
                println "jsonFeature ${jsonFeature} has ${children?.size()} children"
                for (int i = 0; i < children.length(); ++i) {
                    JSONObject childObject = children.getJSONObject(i)
                    println "child object ${childObject}"
                    Feature child = convertJSONToFeature(childObject, sourceFeature, sequence);
                    child.save(failOnError: true)
                    FeatureRelationship fr = new FeatureRelationship();
                    fr.setParentFeature(gsolFeature);
                    fr.setChildFeature(child);
                    fr.save(failOnError: true)
                    child.addToChildFeatureRelationships(fr);
                    gsolFeature.addToParentFeatureRelationships(fr);
                    child.save()
                    gsolFeature.save()
                    println "child ${childObject}"
                    println "fr ${fr}"
                }
            }
            if (jsonFeature.has(FeatureStringEnum.TIMEACCESSION.value)) {
                gsolFeature.setDateCreated(new Date(jsonFeature.getInt(FeatureStringEnum.TIMEACCESSION.value)));
            } else {
                gsolFeature.setDateCreated(new Date());
            }
            if (jsonFeature.has(FeatureStringEnum.TIMELASTMODIFIED.value)) {
                gsolFeature.setLastUpdated(new Date(jsonFeature.getInt(FeatureStringEnum.TIMELASTMODIFIED.value)));
            } else {
                gsolFeature.setLastUpdated(new Date());
            }
            if (jsonFeature.has(FeatureStringEnum.PROPERTIES.value)) {
                JSONArray properties = jsonFeature.getJSONArray(FeatureStringEnum.PROPERTIES.value);
                for (int i = 0; i < properties.length(); ++i) {
                    JSONObject property = properties.getJSONObject(i);
                    JSONObject propertyType = property.getJSONObject(FeatureStringEnum.TYPE.value);
                    FeatureProperty gsolProperty = new FeatureProperty();
                    CV cv = CV.findByName(propertyType.getJSONObject(FeatureStringEnum.CV.value).getString(FeatureStringEnum.NAME.value))
                    CVTerm cvTerm = CVTerm.findByNameAndCv(propertyType.getString(FeatureStringEnum.NAME.value), cv)
//                    gsolProperty.setType(new CVTerm(propertyType.getString("name"), new CV(propertyType.getJSONObject("cv").getString("name"))));
                    gsolProperty.setType(cvTerm);
                    gsolProperty.setValue(property.getString(FeatureStringEnum.VALUE.value));
                    gsolProperty.setFeature(gsolFeature);
                    int rank = 0;
                    for (FeatureProperty fp : gsolFeature.getFeatureProperties()) {
                        if (fp.getType().equals(gsolProperty.getType())) {
                            if (fp.getRank() > rank) {
                                rank = fp.getRank();
                            }
                        }
                    }
                    gsolProperty.setRank(rank + 1);
                    gsolFeature.addToFeatureProperties(gsolProperty);
                }
            }
            if (jsonFeature.has(FeatureStringEnum.DBXREFS.value)) {
                JSONArray dbxrefs = jsonFeature.getJSONArray(FeatureStringEnum.DBXREFS.value);
                for (int i = 0; i < dbxrefs.length(); ++i) {
                    JSONObject dbxref = dbxrefs.getJSONObject(i);
                    JSONObject db = dbxref.getJSONObject(FeatureStringEnum.DB.value);


                    DB newDB = DB.findOrSaveByName(db.getString(FeatureStringEnum.NAME.value))
                    DBXref newDBXref = DBXref.findOrSaveByDbAndAccession(
                            newDB
                            , dbxref.getString(FeatureStringEnum.ACCESSION.value)
                    ).save()
                    gsolFeature.addToFeatureDBXrefs(dbxref)
                    gsolFeature.save()
//                    gsolFeature.addFeatureDBXref(new DB(db.getString("name")), dbxref.getString(FeatureStringEnum.ACCESSION.value));
                }
            }
        }
        catch (JSONException e) {
            log.error(e)
            return null;
        }
        return gsolFeature;
    }

    Organism getOrganism(Feature feature) {
        feature?.featureLocation?.sequence?.organism
    }

    String generateFeatureStringForType(String ontologyId) {
        return generateFeatureForType(ontologyId).cvTerm.toLowerCase()
    }

    String generateFeaturePropertyStringForType(String ontologyId) {
        return generateFeaturePropertyForType(ontologyId)?.cvTerm?.toLowerCase() ?: ontologyId
    }

    FeatureProperty generateFeaturePropertyForType(String ontologyId) {
        switch (ontologyId) {
            case Comment.ontologyId: return new Comment()
            case FeatureAttribute.ontologyId: return new FeatureAttribute()
            case SequenceAttribute.ontologyId: return new SequenceAttribute()
//            case Frameshift.ontologyId: return new Frameshift()
            case TranscriptAttribute.ontologyId: return new TranscriptAttribute()
            case Status.ontologyId: return new Status()
            case Minus1Frameshift.ontologyId: return new Minus1Frameshift()
            case Minus2Frameshift.ontologyId: return new Minus2Frameshift()
            case Plus1Frameshift.ontologyId: return new Plus1Frameshift()
            case Plus2Frameshift.ontologyId: return new Plus2Frameshift()
            default:
                log.error("No feature type exists for ${ontologyId}")
                return null
        }
    }

// TODO: hopefully change in the client and get rid of this ugly code
    Feature generateFeatureForType(String ontologyId) {
        switch (ontologyId) {
            case MRNA.ontologyId: return new MRNA()
            case MiRNA.ontologyId: return new MiRNA()
            case NcRNA.ontologyId: return new NcRNA()
            case SnoRNA.ontologyId: return new SnoRNA()
            case SnRNA.ontologyId: return new SnRNA()
            case RRNA.ontologyId: return new RRNA()
            case TRNA.ontologyId: return new TRNA()
            case Exon.ontologyId: return new Exon()
            case CDS.ontologyId: return new CDS()
            case Intron.ontologyId: return new Intron()
            case Gene.ontologyId: return new Gene()
            case Pseudogene.ontologyId: return new Pseudogene()
            case Transcript.ontologyId: return new Transcript()
            case TransposableElement.ontologyId: return new TransposableElement()
            case RepeatRegion.ontologyId: return new RepeatRegion()
            default:
                log.error("No feature type exists for ${ontologyId}")
                return null
        }

    }
// TODO: hopefully change in the client and get rid of this ugly code
    String convertJSONToOntologyId(JSONObject jsonCVTerm) {
        String cvString = jsonCVTerm.getJSONObject(FeatureStringEnum.CV.value).getString(FeatureStringEnum.NAME.value)
//        CV cv = CV.findOrSaveByName(jsonCVTerm.getJSONObject(FeatureStringEnum.CV.value).getString(FeatureStringEnum.NAME.value))
//        CVTerm cvTerm = CVTerm.findOrSaveByNameAndCv(jsonCVTerm.getString(FeatureStringEnum.NAME.value),cv)
        String cvTermString = jsonCVTerm.getString(FeatureStringEnum.NAME.value)
        println "cvString ${cvString}"
        println "cvTermString ${cvTermString}"

        if (cvString.equalsIgnoreCase(FeatureStringEnum.CV.value) || cvString.equalsIgnoreCase(FeatureStringEnum.SEQUENCE.value)) {
            switch (cvTermString.toUpperCase()) {
                case MRNA.cvTerm.toUpperCase(): return MRNA.ontologyId
                case MiRNA.cvTerm.toUpperCase(): return MiRNA.ontologyId
                case NcRNA.cvTerm.toUpperCase(): return NcRNA.ontologyId
                case SnoRNA.cvTerm.toUpperCase(): return SnoRNA.ontologyId
                case SnRNA.cvTerm.toUpperCase(): return SnRNA.ontologyId
                case RRNA.cvTerm.toUpperCase(): return RRNA.ontologyId
                case TRNA.cvTerm.toUpperCase(): return TRNA.ontologyId
                case Transcript.cvTerm.toUpperCase(): return Transcript.ontologyId
                case Gene.cvTerm.toUpperCase(): return Gene.ontologyId
                case Exon.cvTerm.toUpperCase(): return Exon.ontologyId
                case CDS.cvTerm.toUpperCase(): return CDS.ontologyId
                case Intron.cvTerm.toUpperCase(): return Intron.ontologyId
                case Pseudogene.cvTerm.toUpperCase(): return Pseudogene.ontologyId
                case TransposableElement.cvTerm.toUpperCase(): return TransposableElement.ontologyId
                case RepeatRegion.cvTerm.toUpperCase(): return RepeatRegion.ontologyId
                default:
                    log.error("CV Term not known ${cvTermString} for CV ${FeatureStringEnum.SEQUENCE}")
                    return null
            }
        } else {
            log.error("CV not known ${cvString}")
        }

        return null

    }
//    public CVTerm convertJSONToCVTerm(JSONObject jsonCVTerm) throws JSONException {
//        return new CVTerm(jsonCVTerm.getString("name"), new CV(jsonCVTerm.getJSONObject("cv").getString("name")));
//    }

    void updateGeneBoundaries(Gene gene) {
        if (gene == null) {
            return;
        }
        int geneFmax = Integer.MIN_VALUE;
        int geneFmin = Integer.MAX_VALUE;
        for (Transcript t : transcriptService.getTranscripts(gene)) {
            if (t.getFmin() < geneFmin) {
                geneFmin = t.getFmin();
            }
            if (t.getFmax() > geneFmax) {
                geneFmax = t.getFmax();
            }
        }
        gene.featureLocation.setFmin(geneFmin);
        gene.featureLocation.setFmax(geneFmax);
        gene.setLastUpdated(new Date());
    }

    def setFmin(Feature feature, int fmin) {
        feature.getFeatureLocation().setFmin(fmin);
    }

    def setFmax(Feature feature, int fmax) {
        feature.getFeatureLocation().setFmax(fmax);
    }

/** Convert source feature coordinate to local coordinate.
 *
 * @param sourceCoordinate - Coordinate to convert to local coordinate
 * @return Local coordinate, -1 if source coordinate is <= fmin or >= fmax
 */
    public int convertSourceCoordinateToLocalCoordinate(Feature feature, int sourceCoordinate) {
        if (sourceCoordinate < feature.getFeatureLocation().getFmin() || sourceCoordinate > feature.getFeatureLocation().getFmax()) {
            return -1;
        }
        if (feature.getFeatureLocation().getStrand() == -1) {
            return feature.getFeatureLocation().getFmax() - 1 - sourceCoordinate;
        } else {
            return sourceCoordinate - feature.getFeatureLocation().getFmin();
        }
    }

//    public String getResiduesWithAlterations(Feature feature) {
//        return getResiduesWithAlterations(feature, SequenceAlteration.all);
//    }

    String getResiduesWithAlterations(Feature feature,
                                      Collection<SequenceAlteration> sequenceAlterations = new ArrayList<>()) {
        if (sequenceAlterations.size() == 0) {
            return feature.getResidues();
        }
        StringBuilder residues = new StringBuilder(feature.getResidues());
        FeatureLocation featureLoc = feature.getFeatureLocation();
//        List<SequenceAlteration> orderedSequenceAlterationList = BioObjectUtil.createSortedFeatureListByLocation(sequenceAlterations);

        List<SequenceAlteration> orderedSequenceAlterationList = new ArrayList<>(sequenceAlterations)
        Collections.sort(orderedSequenceAlterationList, new FeaturePositionComparator<SequenceAlteration>());
        if (!feature.getFeatureLocation().getStrand().equals(orderedSequenceAlterationList.get(0).getFeatureLocation().getStrand())) {
            Collections.reverse(orderedSequenceAlterationList);
        }
        int currentOffset = 0;
        for (SequenceAlteration sequenceAlteration : orderedSequenceAlterationList) {
            if (!overlaps(feature, sequenceAlteration, false)) {

            }
//            if (!feature.overlaps(sequenceAlteration, false)) {
//                continue;
//            }
            FeatureLocation sequenceAlterationLoc = sequenceAlteration.getFeatureLocation();
            if (sequenceAlterationLoc.getSourceFeature().equals(featureLoc.getSourceFeature())) {
                int localCoordinate = convertSourceCoordinateToLocalCoordinate(feature, sequenceAlterationLoc.getFmin());
                String sequenceAlterationResidues = sequenceAlteration.getResidues();
                if (feature.getFeatureLocation().getStrand() == -1) {
                    sequenceAlterationResidues = SequenceTranslationHandler.reverseComplementSequence(sequenceAlterationResidues);
                }
                // Insertions
                if (sequenceAlteration instanceof Insertion) {
                    if (feature.getFeatureLocation().getStrand() == -1) {
                        ++localCoordinate;
                    }
                    residues.insert(localCoordinate + currentOffset, sequenceAlterationResidues);
                    currentOffset += sequenceAlterationResidues.length();
                }
                // Deletions
                else if (sequenceAlteration instanceof Deletion) {
                    if (feature.getFeatureLocation().getStrand() == -1) {
                        residues.delete(localCoordinate + currentOffset - sequenceAlteration.getLength() + 1,
                                localCoordinate + currentOffset + 1);
                    } else {
                        residues.delete(localCoordinate + currentOffset,
                                localCoordinate + currentOffset + sequenceAlteration.getLength());
                    }
                    currentOffset -= sequenceAlterationResidues.length();
                }
                // Substitions
                else if (sequenceAlteration instanceof Substitution) {
                    int start = feature.getStrand() == -1 ? localCoordinate - (sequenceAlteration.getLength() - 1) : localCoordinate;
                    residues.replace(start + currentOffset,
                            start + currentOffset + sequenceAlteration.getLength(),
                            sequenceAlterationResidues);
                }
            }
        }
        return residues.toString();
    }

    public void removeFeatureRelationship(Transcript transcript, Feature feature) {

        FeatureRelationship featureRelationship = FeatureRelationship.findByParentFeatureAndChildFeature(transcript, feature)
        if (featureRelationship) {
            FeatureRelationship.deleteAll()
        }

//        CVTerm partOfCvterm = cvTermService.partOf
////        CVTerm exonCvterm = cvTermService.getTerm(type);
//        CVTerm transcriptCvterms = cvTermService.getTerm(FeatureStringEnum.TRANSCRIPT);
//
//        // delete transcript -> exon child relationship
//        for (FeatureRelationship fr : transcript.getChildFeatureRelationships()) {
//            if (partOfCvterm == fr.type
//                    && exonCvterm == fr.childFeature.type
//                    && fr.getSubjectFeature().equals(feature)
//            ) {
//                boolean ok = transcript.getChildFeatureRelationships().remove(fr);
//                break;
//
//            }
//        }

    }

//    public void removeParentFeature(Transcript transcript, Feature feature) {
//
//
//
//        CVTerm partOfCvterm = cvTermService.partOf
////        CVTerm exonCvterm = cvTermService.getTerm(type);
//        CVTerm transcriptCvterms = cvTermService.getTerm(FeatureStringEnum.TRANSCRIPT);
//
//        // delete transcript -> exon child relationship
//        for (FeatureRelationship fr : transcript.getParentFeatureRelationships()) {
//            if (partOfCvterm == fr.type
//                    && transcriptCvterms == fr.parentFeature.type
//                    && fr.getSubjectFeature().equals(feature)
//            ) {
//                boolean ok = feature.getParentFeatureRelationships().remove(fr);
//                break;
//
//            }
//        }
//
//    }

    /**
     * {
     "features": [{
     "location": {
     "fmin": 511,
     "strand": - 1,
     "fmax": 656
     },
     "parent_type": {
     "name": "gene",
     "cv": {
     "name": "sequence"
     }
     },
     "name": "gnl|Amel_4.5|TA31.1_00029673-1",
     "children": [{
     "location": {
     "fmin": 511,
     "strand": - 1,
     "fmax": 656
     },
     "parent_type": {
     "name": "mRNA",
     "cv": {
     "name": "sequence"
     }
     },
     "properties": [{
     "value": "demo",
     "type": {
     "name": "owner",
     "cv": {
     "name": "feature_property"
     }
     }
     }
     ],
     "uniquename": "9690BBB8C6ADF67B972DB9DC2E89E008",
     "type": {
     "name": "exon",
     "cv": {
     "name": "sequence"
     }
     },
     "date_last_modified": 1415046537689,
     "parent_id": "CCBBA69B0C47AD5FA9F0E45710B5E589"
     }, {
     "location": {
     "fmin": 595,
     "strand": - 1,
     "fmax": 622
     },
     "parent_type": {
     "name": "mRNA",
     "cv": {
     "name": "sequence"
     }
     },
     "uniquename": "CCBBA69B0C47AD5FA9F0E45710B5E589-CDS",
     "type": {
     "name": "CDS",
     "cv": {
     "name": "sequence"
     }
     },
     "date_last_modified": 1415046537762,
     "parent_id": "CCBBA69B0C47AD5FA9F0E45710B5E589"
     }
     ],
     "properties": [{
     "value": "demo",
     "type": {
     "name": "owner",
     "cv": {
     "name": "feature_property"
     }
     }
     }
     ],
     "uniquename": "CCBBA69B0C47AD5FA9F0E45710B5E589",
     "type": {
     "name": "mRNA",
     "cv": {
     "name": "sequence"
     }
     },
     "date_last_modified": 1415046537763,
     "parent_id": "E39A4BB7B95876512E0747AF92FB8966"
     }, {
     "location": {
     "fmin": 511,
     "strand": - 1,
     "fmax": 656
     },
     "parent_type": {
     "name": "gene",
     "cv": {
     "name": "sequence"
     }
     },
     "name": "gnl|Amel_4.5|TA31.1_00029673-1",
     "children": [{
     "location": {
     "fmin": 595,
     "strand": - 1,
     "fmax": 622
     },
     "parent_type": {
     "name": "mRNA",
     "cv": {
     "name": "sequence"
     }
     },
     "uniquename": "BFABBEE28A09FF795A839C990EC943C8-CDS",
     "type": {
     "name": "CDS",
     "cv": {
     "name": "sequence"
     }
     },
     "date_last_modified": 1415046565147,
     "parent_id": "BFABBEE28A09FF795A839C990EC943C8"
     }, {
     "location": {
     "fmin": 511,
     "strand": - 1,
     "fmax": 656
     },
     "parent_type": {
     "name": "mRNA",
     "cv": {
     "name": "sequence"
     }
     },
     "properties": [{
     "value": "demo",
     "type": {
     "name": "owner",
     "cv": {
     "name": "feature_property"
     }
     }
     }
     ],
     "uniquename": "B5979F69978E170011AC06CEF73ECF0C",
     "type": {
     "name": "exon",
     "cv": {
     "name": "sequence"
     }
     },
     "date_last_modified": 1415046565146,
     "parent_id": "BFABBEE28A09FF795A839C990EC943C8"
     }
     ],
     "properties": [{
     "value": "demo",
     "type": {
     "name": "owner",
     "cv": {
     "name": "feature_property"
     }
     }
     }
     ],
     "uniquename": "BFABBEE28A09FF795A839C990EC943C8",
     "type": {
     "name": "mRNA",
     "cv": {
     "name": "sequence"
     }
     },
     "date_last_modified": 1415046565148,
     "parent_id": "E39A4BB7B95876512E0747AF92FB8966"
     }, {
     "location": {
     "fmin": 1248,
     "strand": - 1,
     "fmax": 1422
     },
     "parent_type": {
     "name": "gene",
     "cv": {
     "name": "sequence"
     }
     },
     "name": "GB48495-RA",
     "children": [{
     "location": {
     "fmin": 1248,
     "strand": - 1,
     "fmax": 1422
     },
     "parent_type": {
     "name": "mRNA",
     "cv": {
     "name": "sequence"
     }
     },
     "properties": [{
     "value": "demo",
     "type": {
     "name": "owner",
     "cv": {
     "name": "feature_property"
     }
     }
     }
     ],
     "uniquename": "32B569E1FD3A375112A6232940575208",
     "type": {
     "name": "exon",
     "cv": {
     "name": "sequence"
     }
     },
     "date_last_modified": 1415046600891,
     "parent_id": "173CA8771F1CB5855FCC65874ACC16C5"
     }, {
     "location": {
     "fmin": 1248,
     "strand": - 1,
     "fmax": 1422
     },
     "parent_type": {
     "name": "mRNA",
     "cv": {
     "name": "sequence"
     }
     },
     "uniquename": "173CA8771F1CB5855FCC65874ACC16C5-CDS",
     "type": {
     "name": "CDS",
     "cv": {
     "name": "sequence"
     }
     },
     "date_last_modified": 1415046600893,
     "parent_id": "173CA8771F1CB5855FCC65874ACC16C5"
     }
     ],
     "properties": [{
     "value": "demo",
     "type": {
     "name": "owner",
     "cv": {
     "name": "feature_property"
     }
     }
     }
     ],
     "uniquename": "173CA8771F1CB5855FCC65874ACC16C5",
     "type": {
     "name": "mRNA",
     "cv": {
     "name": "sequence"
     }
     },
     "date_last_modified": 1415046600893,
     "parent_id": "62B8EB1512A5752E525D17A42DB37E35"
     }
     ]
     }


     * @param gsolFeature
     * @param includeSequence
     * @return
     */
    JSONObject convertFeatureToJSON(Feature gsolFeature, boolean includeSequence = true) {
        JSONObject jsonFeature = new JSONObject();
        try {
//            jsonFeature.put("type", convertCVTermToJSON(gsolFeature.getType()));
            jsonFeature.put(FeatureStringEnum.TYPE.value, generateJSONFeatureStringForType(gsolFeature.ontologyId));
            jsonFeature.put(FeatureStringEnum.UNIQUENAME.value, gsolFeature.getUniqueName());
            if (gsolFeature.getName() != null) {
                jsonFeature.put(FeatureStringEnum.NAME.value, gsolFeature.getName());
            }
            if (gsolFeature.symbol) {
                jsonFeature.put(FeatureStringEnum.SYMBOL.value, gsolFeature.symbol);
            }
            if (gsolFeature.description) {
                jsonFeature.put(FeatureStringEnum.DESCRIPTION.value, gsolFeature.description);
            }

            // TODO: move this to a configurable place or in another method to process afterwards
            List<String> errorList = new ArrayList<>()
            errorList.addAll(new Cds3Filter().filterFeature(gsolFeature))
            errorList.addAll(new StopCodonFilter().filterFeature(gsolFeature))
            JSONArray notesArray = new JSONArray()
            for(String error : errorList){
                notesArray.put(error)
            }
            jsonFeature.put(FeatureStringEnum.NOTES.value,notesArray)

            // get children
//            Collection<FeatureRelationship> childrenRelationships = gsolFeature.getChildFeatureRelationships();
            Collection<FeatureRelationship> parentRelationships = gsolFeature.parentFeatureRelationships;
            if (parentRelationships) {
                JSONArray children = new JSONArray();
                jsonFeature.put(FeatureStringEnum.CHILDREN.value, children);
                for (FeatureRelationship fr : parentRelationships) {
                    children.put(convertFeatureToJSON(fr.getChildFeature()));
                }
            }
//            Collection<FeatureRelationship> parentRelationships = gsolFeature.getParentFeatureRelationships();
            // get parents
            Collection<FeatureRelationship> childFeatureRelationships = gsolFeature.childFeatureRelationships
            if (childFeatureRelationships?.size() == 1) {
                Feature parent = childFeatureRelationships.iterator().next().getParentFeature();
                jsonFeature.put(FeatureStringEnum.PARENT_ID.value, parent.getUniqueName());
//                jsonFeature.put("parent_type", JSONUtil.convertCVTermToJSON(parent.getType()));
                jsonFeature.put(FeatureStringEnum.PARENT_TYPE.value, generateJSONFeatureStringForType(parent.ontologyId));
            }
            Collection<FeatureLocation> featureLocations = gsolFeature.getFeatureLocations();
            if (featureLocations) {
                FeatureLocation gsolFeatureLocation = featureLocations.iterator().next();
                if (gsolFeatureLocation != null) {
                    jsonFeature.put(FeatureStringEnum.LOCATION.value, convertFeatureLocationToJSON(gsolFeatureLocation));
                }
            }
            if (includeSequence && gsolFeature.getResidues() != null) {
                jsonFeature.put(FeatureStringEnum.RESIDUES.value, gsolFeature.getResidues());
            }
            Collection<FeatureProperty> gsolFeatureProperties = gsolFeature.getFeatureProperties();
            if (gsolFeatureProperties) {
                JSONArray properties = new JSONArray();
                jsonFeature.put(FeatureStringEnum.PROPERTIES.value, properties);
                for (FeatureProperty property : gsolFeatureProperties) {
                    JSONObject jsonProperty = new JSONObject();
//                    jsonProperty.put(FeatureStringEnum.TYPE.value, convertCVTermToJSON(property.getType()));
//                    jsonProperty.put(FeatureStringEnum.TYPE.value, generateFeaturePropertyStringForType(property.ontologyId));
                    JSONObject jsonPropertyType = new JSONObject()
                    jsonPropertyType.put(FeatureStringEnum.NAME.value,property.type)
                    JSONObject jsonPropertyTypeCv = new JSONObject()
                    jsonPropertyTypeCv.put(FeatureStringEnum.NAME.value,FeatureStringEnum.FEATURE_PROPERTY.value)
                    jsonPropertyType.put(FeatureStringEnum.CV.value,jsonPropertyTypeCv)

                    println "jsonPropertyType ${jsonPropertyType}"

                    jsonProperty.put(FeatureStringEnum.TYPE.value, jsonPropertyType);
//                    jsonProperty.put(FeatureStringEnum.TYPE.value, convertCVTermToJSON(property.getType()));
//                    jsonFeature.put(FeatureStringEnum.TYPE.value, generateFeatureStringForType(gsolFeature.ontologyId));
                    jsonProperty.put(FeatureStringEnum.VALUE.value, property.getValue());
                    properties.put(jsonProperty);
                }
            }
            Collection<DBXref> gsolFeatureDbxrefs = gsolFeature.getFeatureDBXrefs();
            if (gsolFeatureDbxrefs) {
                JSONArray dbxrefs = new JSONArray();
                jsonFeature.put(FeatureStringEnum.DBXREFS.value, dbxrefs);
                for (DBXref gsolDbxref : gsolFeatureDbxrefs) {
                    JSONObject dbxref = new JSONObject();
                    dbxref.put(FeatureStringEnum.ACCESSION.value, gsolDbxref.getDbxref().getAccession());
                    dbxref.put(FeatureStringEnum.DB.value, new JSONObject().put(FeatureStringEnum.NAME.value, gsolDbxref.getDbxref().getDb().getName()));
                    dbxrefs.put(dbxref);
                }
            }
//            Date timeLastModified = gsolFeature.getTimeLastModified() != null ? gsolFeature.getTimeLastModified() : gsolFeature.getTimeAccessioned();
            jsonFeature.put(FeatureStringEnum.DATE_LAST_MODIFIED.value, gsolFeature.lastUpdated.time);
            jsonFeature.put(FeatureStringEnum.DATE_CREATION.value, gsolFeature.dateCreated.time);
        }
        catch (JSONException e) {
            return null;
        }
        return jsonFeature;
    }

    JSONObject generateJSONFeatureStringForType(String ontologyId) {
        JSONObject jSONObject = new JSONObject();
        def feature = generateFeatureForType(ontologyId)

        String cvTerm = feature.hasProperty(FeatureStringEnum.ALTERNATECVTERM.value) ? feature.getProperty(FeatureStringEnum.ALTERNATECVTERM.value): feature.cvTerm

        jSONObject.put(FeatureStringEnum.NAME.value,cvTerm)

        JSONObject cvObject = new JSONObject()
        cvObject.put(FeatureStringEnum.NAME.value,FeatureStringEnum.SEQUENCE.value)
        jSONObject.put(FeatureStringEnum.CV.value,cvObject)

        return jSONObject
    }

    JSONObject convertFeatureLocationToJSON(FeatureLocation gsolFeatureLocation) throws JSONException {
        JSONObject jsonFeatureLocation = new JSONObject();
        jsonFeatureLocation.put(FeatureStringEnum.FMIN.value, gsolFeatureLocation.getFmin());
        jsonFeatureLocation.put(FeatureStringEnum.FMAX.value, gsolFeatureLocation.getFmax());
        if (gsolFeatureLocation.isIsFminPartial()) {
            jsonFeatureLocation.put(FeatureStringEnum.IS_FMIN_PARTIAL.value, true);
        }
        if (gsolFeatureLocation.isIsFmaxPartial()) {
            jsonFeatureLocation.put(FeatureStringEnum.IS_FMAX_PARTIAL.value, true);
        }
        jsonFeatureLocation.put(FeatureStringEnum.STRAND.value, gsolFeatureLocation.getStrand());
        return jsonFeatureLocation;
    }

    Boolean deleteFeature(Feature feature,HashMap<String, List<Feature>> modifiedFeaturesUniqueNames) {

        if (feature instanceof Exon) {
            Exon exon = (Exon) feature;
            Transcript transcript = (Transcript) Transcript.findByUniqueName(exonService.getTranscript(exon).getUniqueName());

            if (!(transcriptService.getGene(transcript) instanceof Pseudogene) && transcriptService.isProteinCoding(transcript)) {
                CDS cds = transcriptService.getCDS(transcript);
                if (cdsService.isManuallySetTranslationStart(cds)) {
                    int cdsStart = cds.getStrand() == -1 ? cds.getFmax() : cds.getFmin();
                    if (cdsStart >= exon.getFmin() && cdsStart <= exon.getFmax()) {
                        cdsService.setManuallySetTranslationStart(cds, false);
                    }
                }
            }

            exonService.deleteExon(transcript,exon)
            List<Feature> deletedFeatures = modifiedFeaturesUniqueNames.get(transcript.getUniqueName());
            if (deletedFeatures == null) {
                deletedFeatures = new ArrayList<Feature>();
                modifiedFeaturesUniqueNames.put(transcript.getUniqueName(), deletedFeatures);
            }
            deletedFeatures.add(exon);
            return transcriptService.getExons(transcript)?.size() > 0;
        } else {
            List<Feature> deletedFeatures = modifiedFeaturesUniqueNames.get(feature.getUniqueName());
            if (deletedFeatures == null) {
                deletedFeatures = new ArrayList<Feature>();
                modifiedFeaturesUniqueNames.put(feature.getUniqueName(), deletedFeatures);
            }
            deletedFeatures.add(feature);
            return false;
        }
    }

    /**
     * TODO: finish this method
     * @param modifiedFeaturesUniqueNames
     * @param isUpdateOperation
     */
    def updateModifiedFeaturesAfterDelete(HashMap<String, List<Feature>> modifiedFeaturesUniqueNames, Boolean isUpdateOperation) {
        if(true) throw new RuntimeException("Not implemented")

        for (Map.Entry<String, List<Feature>> entry : modifiedFeaturesUniqueNames.entrySet()) {
            String uniqueName = entry.getKey();
            List<Feature> deletedFeatures = entry.getValue();
            Feature feature = Feature.findByUniqueName(uniqueName);
            if (feature == null) {
                log.info("Feature already deleted");
                continue;
            }
//            SimpleObjectIteratorInterface iterator = feature.getWriteableSimpleObjects(feature.getConfiguration());
//            Feature gsolFeature = (Feature) iterator.next();
//            if (!isUpdateOperation) {
//                featureContainer.getJSONArray("features").put(new JSONObject().put("uniquename", uniqueName));
//
//                if (feature instanceof Transcript) {
//                    Transcript transcript = (Transcript) feature;
//                    Gene gene = transcript.getGene();
//                    editor.deleteTranscript(transcript.getGene(), transcript);
//                    if (gene.getTranscripts().size() == 0) {
//                        editor.deleteFeature(gene);
//                    }
//                    if (dataStore != null) {
//                        if (gene.getTranscripts().size() > 0) {
//                            dataStore.writeFeature(gene);
//                        } else {
//                            dataStore.deleteFeature(gene);
//                        }
//                    }
//
////                    editor.getSession().endTransactionForFeature(gene);
//
//                } else {
//                    editor.deleteFeature(feature);
//                    if (dataStore != null) {
//                        dataStore.deleteFeature(gsolFeature);
//                    }
//
////                    editor.getSession().endTransactionForFeature(feature);
//
//                }
//
//                if (historyStore != null) {
////                    Transaction transaction = new Transaction(operation, feature.getUniqueName());
////                    transaction.setAttribute("feature", feature);
////                    transaction.addOldFeature(feature);
////                    writeHistoryToStore(historyStore, transaction);
//                    List<String> toBeDeleted = new ArrayList<String>();
//                    toBeDeleted.add(feature.getUniqueName());
//                    while (!toBeDeleted.isEmpty()) {
//                        String id = toBeDeleted.remove(toBeDeleted.size() - 1);
//                        for (Transaction t : historyStore.getTransactionListForFeature(id)) {
//                            if (t.getOperation().equals(Transaction.Operation.MERGE_TRANSCRIPTS)) {
//                                if (editor.getSession().getFeatureByUniqueName(t.getOldFeatures().get(1).getUniqueName()) == null) {
//                                    toBeDeleted.add(t.getOldFeatures().get(1).getUniqueName());
//                                }
//                            }
//                        }
//                        historyStore.deleteHistoryForFeature(id);
//                    }
//                }
//            } else {
//                Transaction.Operation operation;
//                if (feature instanceof Transcript) {
//                    Transcript transcript = (Transcript) feature;
//                    calculateCDS(editor, transcript);
//                    findNonCanonicalAcceptorDonorSpliceSites(editor, transcript);
//                    updateTranscriptAttributes(transcript);
//                    operation = Transaction.Operation.DELETE_EXON;
//                    if (dataStore != null) {
//                        dataStore.writeFeature(transcript.getGene());
//                    }
//
////                    editor.getSession().endTransactionForFeature(transcript);
//
//                } else {
//                    operation = Transaction.Operation.DELETE_FEATURE;
//                    if (dataStore != null) {
//                        dataStore.writeFeature(gsolFeature);
//                    }
//
////                    editor.getSession().endTransactionForFeature(feature);
//
//                }
//                if (historyStore != null) {
//                    Transaction transaction = new Transaction(operation, feature.getUniqueName(), username);
////                    transaction.setAttribute("feature", feature);
////                    transaction.setAttribute("children", deletedFeatures);
//                    transaction.getOldFeatures().addAll(deletedFeatures);
//                    if (operation == Transaction.Operation.DELETE_EXON) {
//                        transaction.addNewFeature(feature);
//                    }
//                    writeHistoryToStore(historyStore, transaction);
//                }
//                featureContainer.getJSONArray("features").put(JSONUtil.convertBioFeatureToJSON(editor.getSession().getFeatureByUniqueName(uniqueName)));
//            }
        }

    }
}