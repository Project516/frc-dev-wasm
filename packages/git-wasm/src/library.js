addToLibrary({
  frc_spawn_sync__deps: ['$UTF8ToString', 'malloc'],
  frc_spawn_sync: (argvPtr, argvLen, envPtr, envLen, dirPtr, inFd, inPtr, inLen, outPtrPtr, outLenPtr, errPtrPtr, errLenPtr) => {
    const split = (ptr, len) => {
      const parts = new TextDecoder().decode(HEAPU8.subarray(ptr, ptr + len)).split('\0');
      parts.pop();
      return parts;
    };
    const args = split(argvPtr, argvLen);
    const env = split(envPtr, envLen);
    const dir = dirPtr ? UTF8ToString(dirPtr) : undefined;
    // The child opens the descriptor's file again where this stream stands
    const stream = inFd >= 0 ? FS.getStreamChecked(inFd) : undefined;
    const input = stream ? { node: stream.node, position: stream.position } : HEAPU8.slice(inPtr, inPtr + inLen);
    const result = Module['frcSpawn'](args, env, dir, input);
    const put = (bytes, ptrPtr, lenPtr) => {
      const ptr = _malloc(bytes.length + 1);
      HEAPU8.set(bytes, ptr);
      HEAPU8[ptr + bytes.length] = 0;
      HEAPU32[ptrPtr >> 2] = ptr;
      HEAP32[lenPtr >> 2] = bytes.length;
    };
    put(result.stdout, outPtrPtr, outLenPtr);
    put(result.stderr, errPtrPtr, errLenPtr);
    return result.status;
  },
  frc_helper_open__deps: ['$UTF8ToString'],
  frc_helper_open: (namePtr, urlPtr) => {
    Module['frcNewSession'](UTF8ToString(urlPtr));
  }
});
