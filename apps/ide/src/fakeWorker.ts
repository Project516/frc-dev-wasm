// Lets Vite bundle the worker while the workbench decides how to start it.
export class Worker {
  constructor(
    public url: string | URL,
    public options?: WorkerOptions
  ) {}
}
