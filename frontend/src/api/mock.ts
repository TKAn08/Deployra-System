import { ApiError } from "./client";

export type MockResult<T> = { data: T; mocked: boolean };

export async function fetchOrMock<T>(call: () => Promise<T>, mock: T): Promise<MockResult<T>> {
  try {
    return { data: await call(), mocked: false };
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) {
      return { data: mock, mocked: true };
    }
    throw err;
  }
}