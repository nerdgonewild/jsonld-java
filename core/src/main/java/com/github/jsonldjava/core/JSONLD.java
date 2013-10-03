package com.github.jsonldjava.core;

import static com.github.jsonldjava.core.JSONLDUtils.createNodeMap;
import static com.github.jsonldjava.core.JSONLDUtils.isArray;
import static com.github.jsonldjava.core.JSONLDUtils.isObject;
import static com.github.jsonldjava.core.JSONLDUtils.isString;
import static com.github.jsonldjava.core.JSONLDUtils.removePreserve;
import static com.github.jsonldjava.core.JSONLDUtils.resolveContextUrls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.jsonldjava.core.JsonLdError.Error;
import com.github.jsonldjava.impl.NQuadRDFParser;
import com.github.jsonldjava.impl.NQuadTripleCallback;
import com.github.jsonldjava.impl.TurtleRDFParser;
import com.github.jsonldjava.impl.TurtleTripleCallback;

public class JSONLD {

    /**
     * Performs JSON-LD compaction.
     * 
     * @param input
     *            the JSON-LD input to compact.
     * @param ctx
     *            the context to compact with.
     * @param [options] options to use: [base] the base IRI to use. [strict] use
     *        strict mode (default: true). [compactArrays] true to compact
     *        arrays to single values when appropriate, false not to (default:
     *        true). [graph] true to always output a top-level graph (default:
     *        false). [skipExpansion] true to assume the input is expanded and
     *        skip expansion, false not to, defaults to false. [loadContext(url,
     *        callback(err, url, result))] the context loader.
     * @param callback
     *            (err, compacted, ctx) called once the operation completes.
     */
    public static Object compact(Object input, Object ctx, Options opts)
            throws JsonLdError {
        // nothing to compact
        if (input == null) {
            return null;
        }

        // NOTE: javascript does this check before input check
        if (ctx == null) {
            throw new JsonLdError("The compaction context must not be null.")
                    .setType(JsonLdError.Error.COMPACT_ERROR);
        }

        // set default options
        if (opts.base == null) {
            opts.base = "";
        }
        if (opts.strict == null) {
            opts.strict = true;
        }
        if (opts.compactArrays == null) {
            opts.compactArrays = true;
        }
        if (opts.graph == null) {
            opts.graph = false;
        }
        if (opts.skipExpansion == null) {
            opts.skipExpansion = false;
        }
        // JSONLDProcessor p = new JSONLDProcessor(opts);

        // expand input then do compaction
        Object expanded;
        try {
            if (opts.skipExpansion) {
                expanded = input;
            } else {
                expanded = JSONLD.expand(input, opts);
            }
        } catch (final JsonLdError e) {
            throw new JsonLdError("Could not expand input before compaction.").setType(
                    JsonLdError.Error.COMPACT_ERROR).setDetail("cause", e);
        }

        // process context
        Context activeCtx = new Context(opts);
        try {
            activeCtx = activeCtx.parse(ctx);
        } catch (final JsonLdError e) {
            throw new JsonLdError("Could not process context before compaction.")
                    .setType(JsonLdError.Error.COMPACT_ERROR).setDetail("cause", e);
        }

        // do compaction
        Object compacted = new JSONLDProcessor(opts).compact(activeCtx, null, expanded);

        // cleanup
        if (opts.compactArrays && !opts.graph && isArray(compacted)) {
            // simplify to a single item
            if (((List<Object>) compacted).size() == 1) {
                compacted = ((List<Object>) compacted).get(0);
            }
            // simplify to an empty object
            else if (((List<Object>) compacted).size() == 0) {
                compacted = new LinkedHashMap<String, Object>();
            }
        }
        // always use array if graph option is on
        else if (opts.graph && isObject(compacted)) {
            final List<Object> tmp = new ArrayList<Object>();
            tmp.add(compacted);
            compacted = tmp;
        }

        // follow @context key
        if (isObject(ctx) && ((Map<String, Object>) ctx).containsKey("@context")) {
            ctx = ((Map<String, Object>) ctx).get("@context");
        }

        // build output context
        ctx = JSONLDUtils.clone(ctx);
        if (!isArray(ctx)) {
            final List<Object> lctx = new ArrayList<Object>();
            lctx.add(ctx);
            ctx = lctx;
        }

        // remove empty contexts
        final List<Object> tmp = (List<Object>) ctx;
        ctx = new ArrayList<Object>();
        for (final Object i : tmp) {
            if (!isObject(i) || ((Map) i).size() > 0) {
                ((List<Object>) ctx).add(i);
            }
        }

        // remove array if only one context
        final boolean hasContext = ((List) ctx).size() > 0;
        if (((List) ctx).size() == 1) {
            ctx = ((List) ctx).get(0);
        }

        // add context and/or @graph
        if (isArray(compacted)) {
            final String kwgraph = activeCtx.compactIri("@graph");
            final Object graph = compacted;
            compacted = new LinkedHashMap<String, Object>();
            if (hasContext) {
                ((Map<String, Object>) compacted).put("@context", ctx);
            }
            ((Map<String, Object>) compacted).put(kwgraph, graph);
        } else if (isObject(compacted) && hasContext) {
            // reorder keys so @context is first
            final Map<String, Object> graph = (Map<String, Object>) compacted;
            compacted = new LinkedHashMap<String, Object>();
            ((Map) compacted).put("@context", ctx);
            for (final String key : graph.keySet()) {
                ((Map<String, Object>) compacted).put(key, graph.get(key));
            }
        }

        // frame needs the value of the compaction result's activeCtx
        opts.compactResultsActiveCtx = activeCtx;
        return compacted;
    }

    public static Object compact(Object input, Map<String, Object> ctx)
            throws JsonLdError {
        return compact(input, ctx, new Options("", true));
    }

    /**
     * Performs JSON-LD expansion.
     * 
     * @param input
     *            the JSON-LD input to expand.
     * @param [options] the options to use: [base] the base IRI to use.
     *        [keepFreeFloatingNodes] true to keep free-floating nodes, false
     *        not to, defaults to false.
     * @return the expanded result as a list
     */
    public static List<Object> expand(Object input, Options opts) throws JsonLdError {
        if (opts.base == null) {
            opts.base = "";
        }

        if (opts.keepFreeFloatingNodes == null) {
            opts.keepFreeFloatingNodes = false;
        }

        // resolve all @context URLs in the input
        input = JSONLDUtils.clone(input);
        JSONLDUtils.resolveContextUrls(input);

        // do expansion
        final JSONLDProcessor p = new JSONLDProcessor(opts);
        Object expanded = p.expand(new Context(opts), null, input, false);

        // optimize away @graph with no other properties
        if (isObject(expanded) && ((Map) expanded).containsKey("@graph")
                && ((Map) expanded).size() == 1) {
            expanded = ((Map<String, Object>) expanded).get("@graph");
        } else if (expanded == null) {
            expanded = new ArrayList<Object>();
        }

        // normalize to an array
        if (!isArray(expanded)) {
            final List<Object> tmp = new ArrayList<Object>();
            tmp.add(expanded);
            expanded = tmp;
        }
        return (List<Object>) expanded;
    }

    public static List<Object> expand(Object input) throws JsonLdError {
        return expand(input, new Options(""));
    }

    /**
     * Performs JSON-LD flattening.
     * 
     * @param input
     *            the JSON-LD to flatten.
     * @param ctx
     *            the context to use to compact the flattened output, or null.
     * @param [options] the options to use: [base] the base IRI to use.
     *        [loadContext(url, callback(err, url, result))] the context loader.
     * @param callback
     *            (err, flattened) called once the operation completes.
     * @throws JsonLdError
     */
    public static Object flatten(Object input, Object ctx, Options opts)
            throws JsonLdError {
        // set default options
        if (opts.base == null) {
            opts.base = "";
        }

        // expand input
        List<Object> _input;
        try {
            _input = expand(input, opts);
        } catch (final JsonLdError e) {
            throw new JsonLdError("Could not expand input before flattening.").setType(
                    JsonLdError.Error.FLATTEN_ERROR).setDetail("cause", e);
        }

        final Object flattened = new JSONLDProcessor(opts).flatten(_input);

        if (ctx == null) {
            return flattened;
        }

        // compact result (force @graph option to true, skip expansion)
        opts.graph = true;
        opts.skipExpansion = true;
        try {
            final Object compacted = compact(flattened, ctx, opts);
            return compacted;
        } catch (final JsonLdError e) {
            throw new JsonLdError("Could not compact flattened output.").setType(
                    JsonLdError.Error.FLATTEN_ERROR).setDetail("cause", e);
        }
    }

    public static Object flatten(Object input, Object ctxOrOptions) throws JsonLdError {
        if (ctxOrOptions instanceof Options) {
            return flatten(input, null, (Options) ctxOrOptions);
        } else {
            return flatten(input, ctxOrOptions, new Options(""));
        }
    }

    public static Object flatten(Object input) throws JsonLdError {
        return flatten(input, null, new Options(""));
    }

    /**
     * Performs JSON-LD framing.
     * 
     * @param input
     *            the JSON-LD input to frame.
     * @param frame
     *            the JSON-LD frame to use.
     * @param [options] the framing options. [base] the base IRI to use. [embed]
     *        default @embed flag (default: true). [explicit] default @explicit
     *        flag (default: false). [omitDefault] default @omitDefault flag
     *        (default: false). [loadContext(url, callback(err, url, result))]
     *        the context loader.
     * @param callback
     *            (err, framed) called once the operation completes.
     * @throws JsonLdError
     */
    public static Object frame(Object input, Map<String, Object> frame, Options options)
            throws JsonLdError {
        // set default options
        if (options.base == null) {
            options.base = "";
        }
        if (options.embed == null) {
            options.embed = true;
        }
        if (options.explicit == null) {
            options.explicit = false;
        }
        if (options.omitDefault == null) {
            options.omitDefault = false;
        }

        // TODO: add sanity checks for input and throw JSONLDProcessingErrors
        // when incorrect input is used
        // preserve frame context
        final Object ctx = frame.containsKey("@context") ? frame.get("@context")
                : new LinkedHashMap<String, Object>();

        // expand input
        Object expanded;
        try {
            expanded = JSONLD.expand(input, options);
        } catch (final JsonLdError e) {
            throw new JsonLdError("Could not expand input before framing.").setType(
                    JsonLdError.Error.FRAME_ERROR).setDetail("cause", e);
        }
        // expand frame
        Object expandedFrame;
        final Options opts = options.clone();
        opts.keepFreeFloatingNodes = true;
        try {
            expandedFrame = JSONLD.expand(frame, opts);
        } catch (final JsonLdError e) {
            throw new JsonLdError("Could not expand frame before framing.").setType(
                    JsonLdError.Error.FRAME_ERROR).setDetail("cause", e);
        }

        // do framing
        final Object framed = new JSONLDProcessor(opts).frame(expanded, expandedFrame);
        // compact results (force @graph option to true, skip expansion)
        opts.graph = true;
        opts.skipExpansion = true;
        try {
            final Object compacted = compact(framed, ctx, opts);
            // get resulting activeCtx
            final Context actx = opts.compactResultsActiveCtx;
            // get graph alias
            final String graph = actx.compactIri("@graph");
            ((Map<String, Object>) compacted).put(graph,
                    removePreserve(actx, ((Map<String, Object>) compacted).get(graph), opts));
            return compacted;
        } catch (final JsonLdError e) {
            throw new JsonLdError("Could not compact framed output.").setType(
                    JsonLdError.Error.FRAME_ERROR).setDetail("cause", e);
        }
    }

    public static Object frame(Object input, Map<String, Object> frame)
            throws JsonLdError {
        return frame(input, frame, new Options(""));
    }

    /**
     * Performs RDF dataset normalization on the given JSON-LD input. The output
     * is an RDF dataset unless the 'format' option is used.
     * 
     * @param input
     *            the JSON-LD input to normalize.
     * @param [options] the options to use: [base] the base IRI to use. [format]
     *        the format if output is a string: 'application/nquads' for
     *        N-Quads. [loadContext(url, callback(err, url, result))] the
     *        context loader.
     * @param callback
     *            (err, normalized) called once the operation completes.
     * @throws JsonLdError
     */
    public static Object normalize(Object input, Options options) throws JsonLdError {
        if (options.base == null) {
            options.base = "";
        }

        final Options opts = options.clone();
        opts.format = null;
        RDFDataset dataset;
        try {
            dataset = (RDFDataset) toRDF(input, opts);
        } catch (final JsonLdError e) {
            throw new JsonLdError(
                    "Could not convert input to RDF dataset before normalization.").setType(
                    JsonLdError.Error.NORMALIZE_ERROR).setDetail("cause", e);
        }
        return new JSONLDProcessor(options).normalize(dataset);
    }

    public static Object normalize(Object input) throws JsonLdError {
        return normalize(input, new Options(""));
    }

    /**
     * Outputs the RDF dataset found in the given JSON-LD object.
     * 
     * @param input
     *            the JSON-LD input.
     * @param callback
     *            A callback that is called when the input has been converted to
     *            Quads (null to use options.format instead).
     * @param [options] the options to use: [base] the base IRI to use. [format]
     *        the format to use to output a string: 'application/nquads' for
     *        N-Quads (default). [loadContext(url, callback(err, url, result))]
     *        the context loader.
     * @param callback
     *            (err, dataset) called once the operation completes.
     */
    public static Object toRDF(Object input, JSONLDTripleCallback callback, Options options)
            throws JsonLdError {
        if (options.base == null) {
            options.base = "";
        }

        Object expanded;
        try {
            expanded = JSONLD.expand(input, options);
        } catch (final JsonLdError e) {
            throw new JsonLdError("Could not expand input before conversion to RDF.")
                    .setType(JsonLdError.Error.RDF_ERROR).setDetail("cause", e);
        }

        final UniqueNamer namer = new UniqueNamer("_:b");
        final Map<String, Object> nodeMap = new LinkedHashMap<String, Object>() {
            {
                put("@default", new LinkedHashMap<String, Object>());
            }
        };
        createNodeMap(expanded, nodeMap, "@default", namer);

        // output RDF dataset
        final RDFDataset dataset = new JSONLDProcessor(options).toRDF(nodeMap);

        // generate namespaces from context
        if (options.useNamespaces) {
            List<Map<String, Object>> _input;
            if (isArray(input)) {
                _input = (List<Map<String, Object>>) input;
            } else {
                _input = new ArrayList<Map<String, Object>>();
                _input.add((Map<String, Object>) input);
            }
            for (final Map<String, Object> e : _input) {
                if (e.containsKey("@context")) {
                    dataset.parseContext((Map<String, Object>) e.get("@context"));
                }
            }
        }

        if (callback != null) {
            return callback.call(dataset);
        }

        if (options.format != null) {
            if ("application/nquads".equals(options.format)) {
                return new NQuadTripleCallback().call(dataset);
            } else if ("text/turtle".equals(options.format)) {
                return new TurtleTripleCallback().call(dataset);
            } else {
                throw new JsonLdError("Unknown output format.").setType(
                        JsonLdError.Error.UNKNOWN_FORMAT).setDetail("format",
                        options.format);
            }
        }
        return dataset;
    }

    public static Object toRDF(Object input, Options options) throws JsonLdError {
        return toRDF(input, null, options);
    }

    public static Object toRDF(Object input, JSONLDTripleCallback callback)
            throws JsonLdError {
        return toRDF(input, callback, new Options(""));
    }

    public static Object toRDF(Object input) throws JsonLdError {
        return toRDF(input, new Options(""));
    }

    /**
     * a registry for RDF Parsers (in this case, JSONLDSerializers) used by
     * fromRDF if no specific serializer is specified and options.format is set.
     */
    private static Map<String, RDFParser> rdfParsers = new LinkedHashMap<String, RDFParser>() {
        {
            // automatically register nquad serializer
            put("application/nquads", new NQuadRDFParser());
            put("text/turtle", new TurtleRDFParser());
        }
    };

    public static void registerRDFParser(String format, RDFParser parser) {
        rdfParsers.put(format, parser);
    }

    public static void removeRDFParser(String format) {
        rdfParsers.remove(format);
    }

    /**
     * Converts an RDF dataset to JSON-LD.
     * 
     * @param dataset
     *            a serialized string of RDF in a format specified by the format
     *            option or an RDF dataset to convert.
     * @param [options] the options to use: [format] the format if input is not
     *        an array: 'application/nquads' for N-Quads (default). [useRdfType]
     *        true to use rdf:type, false to use @type (default: false).
     *        [useNativeTypes] true to convert XSD types into native types
     *        (boolean, integer, double), false not to (default: true).
     * 
     * @param callback
     *            (err, output) called once the operation completes.
     */
    public static Object fromRDF(Object dataset, Options options) throws JsonLdError {
        // handle non specified serializer case

        RDFParser parser = null;

        if (options.format == null && dataset instanceof String) {
            // attempt to parse the input as nquads
            options.format = "application/nquads";
        }

        if (rdfParsers.containsKey(options.format)) {
            parser = rdfParsers.get(options.format);
        } else {
            throw new JsonLdError("Unknown input format.").setType(
                    JsonLdError.Error.UNKNOWN_FORMAT).setDetail("format", options.format);
        }

        // convert from RDF
        return fromRDF(dataset, options, parser);
    }

    public static Object fromRDF(Object dataset) throws JsonLdError {
        return fromRDF(dataset, new Options(""));
    }

    /**
     * Uses a specific serializer.
     * 
     */
    public static Object fromRDF(Object input, Options options, RDFParser parser)
            throws JsonLdError {
        if (options.useRdfType == null) {
            options.useRdfType = false;
        }
        if (options.useNativeTypes == null) {
            options.useNativeTypes = true;
        }

        final RDFDataset dataset = parser.parse(input);

        // convert from RDF
        final Object rval = new JSONLDProcessor(options).fromRDF(dataset);

        // re-process using the generated context if outputForm is set
        if (options.outputForm != null) {
            if ("expanded".equals(options.outputForm)) {
                return rval;
            } else if ("compacted".equals(options.outputForm)) {
                return compact(rval, dataset.getContext(), options);
            } else if ("flattened".equals(options.outputForm)) {
                return flatten(rval, dataset.getContext(), options);
            } else {
                throw new JsonLdError("Unknown value for output form").setType(
                        Error.INVALID_INPUT).setDetail("outputForm", options.outputForm);
            }
        }
        return rval;
    }

    public static Object fromRDF(Object input, RDFParser parser) throws JsonLdError {
        return fromRDF(input, new Options(""), parser);
    }

    public static Object simplify(Object input, Options opts) throws JsonLdError {
        // TODO Auto-generated method stub
        if (opts.base == null) {
            opts.base = "";
        }
        return new JSONLDProcessor(opts).simplify(input);
    }

    public static Object simplify(Object input) throws JsonLdError {
        return simplify(input, new Options(""));
    }
}
