export function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => { values.set(key, value); },
    removeItem: (key) => { values.delete(key); }, clear: () => values.clear(), key: (index) => [...values.keys()][index] ?? null,
    get length() { return values.size; } };
}
