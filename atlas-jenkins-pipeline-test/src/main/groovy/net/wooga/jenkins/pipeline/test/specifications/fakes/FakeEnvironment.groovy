package net.wooga.jenkins.pipeline.test.specifications.fakes

class FakeEnvironment {
    final List<Map> used
    final Binding binding

    FakeEnvironment(Binding binding) {
        this.used = new ArrayList<>()
        this.binding = binding
    }

    String getAt(String key) {
        return binding.env[key]
    }

    void putAt(String key, String value) {
        binding.env[key] = value
        binding.variables[key] = value
    }

    void runWithEnv(List<String> envStrs, Closure cls) {
        def envMap = envStrs.
                collect{it.toString()}.
                collectEntries{String envStr -> [(envStr.split("=")[0]): envStr.split("=")[1]]}
        runWithEnv(envMap, cls)
    }
    void runWithEnv(Map env, Closure cls) {
        binding.env.putAll(env)
        binding.variables.putAll(env)
        try {
            cls()
        } finally {
            used.add(deepCopy(binding.env as Map))
            env.each {
                binding.env.remove(it.key)
                binding.variables.remove(it.key)
            }
        }
    }

    protected static Map deepCopy(Map orig) {
        def bos = new ByteArrayOutputStream()
        def oos = new ObjectOutputStream(bos)
        oos.writeObject(orig); oos.flush()
        def bin = new ByteArrayInputStream(bos.toByteArray())
        def ois = new ObjectInputStream(bin)
        return ois.readObject() as Map
    }

    void wipe() {
        used.clear()
    }
}
