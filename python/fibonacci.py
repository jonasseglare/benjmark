import benjmark

keys = ["cpp", "clojure"];

root = "../benchmarks/fibonacci";

settings = benjmark.default_settings.set('outputprefix', 'tmpfib').set('sizeformat', '{:d} points').set('xlabel', 'Nth number');

benjmark.render_barplots(keys, root, settings);

benjmark.render_lineplot(keys, root, settings);
