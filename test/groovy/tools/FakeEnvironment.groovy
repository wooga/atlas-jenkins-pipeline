package tools

class FakeEnvironment {
    final List<Map> usedEnvironments
    final Binding binding

    FakeEnvironment(Binding binding) {
        this.usedEnvironments = new ArrayList<>()
        this.binding = binding
    }

    String getAt(String key) {
        return binding.env[key]
    }

    void putAt(String key, String value) {
        binding.env[key] = value
        binding.variables[key] = value
    }

    def runWithEnv(Map env, Closure cls) {
        binding.env.putAll(env)
        binding.variables.putAll(env)
        try {
            cls()
        } finally {
            usedEnvironments.add(deepCopy(binding.env as Map))
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
        usedEnvironments.clear()
    }
}
