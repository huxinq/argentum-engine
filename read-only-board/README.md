# Argentum read-only board primitives

This package contains store-free presentation components that can be reused by
Argentum's live client and by tools that display recorded public game state.

It deliberately contains no rules logic, engine store access, action dispatch,
hidden-state lookup, or policy feature construction. Consumers supply already
authorized public view data. The current exports are the shared speed gauge and
the deterministic land/nonland battlefield partition used by the replay
inspector.
