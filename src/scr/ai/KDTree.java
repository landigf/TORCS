package scr.ai;

import java.io.*;
import java.util.*;

public class KDTree implements Serializable {
    private static final long serialVersionUID = 1L;
    private transient int dims;
    private Node root;

    private static class Node implements Serializable {
        private static final long serialVersionUID = 1L;
        DataPoint p;
        Node left, right;
        int axis;
        Node(DataPoint p, int axis) { this.p = p; this.axis = axis; }
    }

    public KDTree(List<DataPoint> points, int dims) {
        this.dims = dims;
        this.root = build(points, 0);
    }

    private void readObject(ObjectInputStream ois)
            throws IOException, ClassNotFoundException {
        ois.defaultReadObject();
        Node n = root;
        while (n.left != null) n = n.left;
        this.dims = n.p.features.length;
    }

    private Node build(List<DataPoint> pts, int depth) {
        if (pts.isEmpty()) return null;
        int axis = depth % dims;
        pts.sort(Comparator.comparingDouble(p -> p.features[axis]));
        int mid = pts.size() / 2;
        Node node = new Node(pts.get(mid), axis);
        node.left  = build(pts.subList(0, mid), depth + 1);
        node.right = build(pts.subList(mid + 1, pts.size()), depth + 1);
        return node;
    }

    public List<DataPoint> nearest(double[] target, int k) {
        PriorityQueue<DataPointDist> pq = new PriorityQueue<>(
            Comparator.comparingDouble(d -> -d.dist)
        );
        search(root, target, k, pq);
        List<DataPoint> result = new ArrayList<>();
        for (DataPointDist dd : pq) result.add(dd.p);
        return result;
    }

    private void search(Node node, double[] target, int k, PriorityQueue<DataPointDist> pq) {
        if (node == null) return;
        double d = dist(target, node.p.features);
        pq.offer(new DataPointDist(node.p, d));
        if (pq.size() > k) pq.poll();

        int ax = node.axis;
        Node near = target[ax] < node.p.features[ax] ? node.left : node.right;
        Node far  = target[ax] < node.p.features[ax] ? node.right : node.left;
        search(near, target, k, pq);
        if (!pq.isEmpty() && Math.abs(target[ax] - node.p.features[ax]) < pq.peek().dist) {
            search(far, target, k, pq);
        }
    }

    private static class DataPointDist {
        DataPoint p;
        double dist;
        DataPointDist(DataPoint p, double dist) { this.p = p; this.dist = dist; }
    }

    private static double dist(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i]; sum += d * d;
        }
        return Math.sqrt(sum);
    }
}