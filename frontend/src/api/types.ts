export type Me = {
  id: number;
  email: string;
  firstName: string | null;
  lastName: string | null;
  planCode: string;
  planName: string;
  admin: boolean;
};

export type ProjectSummary = {
  id: number;
  name: string;
  createdAt: string;
  serviceCount: number;
};

export type ServiceSummary = {
  id: number;
  projectId: number;
  name: string;
  slug: string;
  type: "APP" | "DATABASE";
  status: string;
  repoUrl: string | null;
  branch: string | null;
  port: number | null;
  memoryMb: number;
  createdAt: string;
};

export type ProjectDetail = {
  id: number;
  name: string;
  createdAt: string;
  services: ServiceSummary[];
};

export type ServiceDetail = {
  id: number;
  projectId: number;
  projectName: string;
  name: string;
  slug: string;
  type: "APP" | "DATABASE";
  status: string;
  repoUrl: string | null;
  branch: string | null;
  rootDir: string | null;
  dockerfilePath: string | null;
  port: number | null;
  healthcheckPath: string | null;
  memoryMb: number;
  cpuMillicores: number;
  suspendedReason: string | null;
  lastActiveAt: string | null;
  createdAt: string;
};

export type DeploymentView = {
  id: number;
  status: string;
  trigger: string;
  sourceType: string;
  commitSha: string | null;
  image: string | null;
  buildSeconds: number | null;
  buildLog: string | null;
  createdAt: string;
  finishedAt: string | null;
};

export type PlanView = {
  id: number;
  code: string;
  name: string;
  price: number;
  currency: string;
  billingPeriodDays: number;
  limits: Record<string, number | null>;
};

export type AddonView = {
  id: number;
  name: string;
  entitlementKey: string;
  delta: number;
  validityType: "DAYS" | "CALENDAR_MONTH";
  validityDays: number | null;
  price: number;
};

export type QuotaLine = {
  key: string;
  description: string;
  unit: string;
  unlimited: boolean;
  limit: number | null;
  used: number;
};

export type QuotaAddon = {
  id: number;
  name: string;
  entitlementKey: string;
  delta: number;
  expiresAt: string;
};

export type QuotaReport = {
  planCode: string;
  planName: string;
  lines: QuotaLine[];
  activeAddons: QuotaAddon[];
};

export type PurchaseResponse = {
  paymentId: number;
  addonId: number;
  expiresAt: string;
  amount: number;
  currency: string;
};

export type AdminOverview = {
  users: number;
  projects: number;
  runningServices: number;
  failedServices: number;
};

export type AdminUser = {
  id: number;
  email: string;
  firstName: string | null;
  lastName: string | null;
  active: boolean;
  planCode: string;
  createdAt: string;
  admin: boolean;
};

export type AdminOverride = {
  id: number;
  entitlementKey: string;
  limitValue: number | null;
  reason: string | null;
  expiresAt: string | null;
};

export type AdminUserDetail = {
  user: AdminUser;
  overrides: AdminOverride[];
};

export type AdminServiceRow = {
  id: number;
  name: string;
  slug: string;
  ownerEmail: string;
  projectId: number;
  projectName: string;
  type: "APP" | "DATABASE";
  status: string;
  suspendedReason: string | null;
  memoryMb: number;
};

export type AdminKey = {
  code: string;
  description: string;
  unit: string;
};